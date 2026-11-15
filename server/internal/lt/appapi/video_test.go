package appapi

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/guohuiyuan/music-lib/model"
)

const testBV = "BV1xx411c7mD|123"

func stubVideo(t *testing.T, a *API) {
	t.Helper()
	search := a.Search
	a.Search = func(source, keyword string) ([]model.Song, error) {
		if source == "bilibili" {
			return []model.Song{{ID: testBV, Source: "bilibili", Name: "测试视频", Artist: "UP 主", Duration: 600}}, nil
		}
		return search(source, keyword)
	}
	deadline := time.Now().Add(2 * time.Hour).Unix()
	a.Videos.Fetch = func(bvid, cid string, qn int) ([]byte, error) {
		return []byte(fmt.Sprintf(`{"code":0,"data":{"timelength":600000,"accept_quality":[80,64],"accept_description":["1080P","720P"],
		"dash":{"video":[{"id":80,"baseUrl":"https://upos.example/v80.m4s?deadline=%d","codecid":7},{"id":64,"baseUrl":"https://upos.example/v64.m4s?deadline=%d","codecid":7}],
		"audio":[{"id":30280,"baseUrl":"https://upos.example/a.m4s?deadline=%d"}]}}}`, deadline, deadline, deadline)), nil
	}
}

func adminJSON(t *testing.T, method, url string, body any, out any) int {
	t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(method, url, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func TestVideoEndpoint(t *testing.T) {
	srv, a := newTestServer(t)
	stubVideo(t, a)
	alice := join(t, srv, a, "alice")
	var res struct {
		Video      struct{ URL string }
		ProxyVideo string
		Qn         int
	}
	if code := getJSON(t, srv.URL+Prefix+"/video/"+testBV, alice, &res); code != 200 {
		t.Fatalf("status %d", code)
	}
	if res.Qn != 80 || !strings.Contains(res.Video.URL, "v80") || res.ProxyVideo != Prefix+"/vstream/BV1xx411c7mD%7C123/video?qn=64" {
		t.Fatalf("video = %+v", res)
	}
	if code := getJSON(t, srv.URL+Prefix+"/video/notabv", alice, nil); code != 400 {
		t.Fatalf("bad id status %d", code)
	}
}

func TestVideoRoomFlow(t *testing.T) {
	srv, a := newTestServer(t)
	stubVideo(t, a)
	alice := join(t, srv, a, "alice")
	bob := join(t, srv, a, "bob")

	// 没有管理员时不能把默认档位设成「管理员做主」。
	if code := adminJSON(t, "PUT", srv.URL+"/music/lt/video_settings", map[string]any{"policy": "admins"}, nil); code != 400 {
		t.Fatalf("admins without admin status %d", code)
	}

	ca, _ := dial(t, srv, alice, "")
	created := ca.call("create_room", map[string]string{"kind": "video"})
	code := created["d"].(map[string]any)["roomCode"].(string)
	snap := ca.waitFor("snapshot", nil)
	if snap["kind"] != "video" || snap["video"] == nil {
		t.Fatalf("snapshot = %v", snap)
	}
	cb, _ := dial(t, srv, bob, code)
	cb.waitFor("snapshot", nil)

	var search struct{ Tracks []struct{ ID string } }
	getJSON(t, srv.URL+Prefix+"/search?source=bilibili&q="+"BV1xx411c7mD", alice, &search)
	if ack := ca.call("add_song", map[string]string{"source": "netease", "id": "1"}); ack["ok"] == true {
		t.Fatal("non-bilibili should be rejected in video room")
	}
	if ack := ca.call("add_song", map[string]string{"source": "bilibili", "id": testBV}); ack["ok"] != true {
		t.Fatalf("add video = %v", ack)
	}
	pb := cb.waitFor("playback", func(d map[string]any) bool {
		return d["playback"].(map[string]any)["status"] == "preparing"
	})
	seq := pb["playback"].(map[string]any)["seq"]
	ca.call("track_status", map[string]any{"seq": seq, "status": "ready"})
	cb.call("track_status", map[string]any{"seq": seq, "status": "ready"})
	cb.waitFor("playback", func(d map[string]any) bool {
		return d["playback"].(map[string]any)["status"] == "playing"
	})

	// 默认「点播人做主」：bob 的暂停变成请求，alice 同意后暂停。
	ack := cb.call("control", map[string]any{"action": "pause", "seq": seq})
	if ack["d"].(map[string]any)["requested"] != true {
		t.Fatalf("bob pause ack = %v", ack)
	}
	v := ca.waitFor("video", func(d map[string]any) bool {
		reqs, _ := d["video"].(map[string]any)["requests"].([]any)
		return len(reqs) == 1
	})
	reqID := v["video"].(map[string]any)["requests"].([]any)[0].(map[string]any)["id"]
	if ack := ca.call("control_reply", map[string]any{"requestId": reqID, "accept": true}); ack["ok"] != true {
		t.Fatalf("reply = %v", ack)
	}
	cb.waitFor("playback", func(d map[string]any) bool {
		return d["playback"].(map[string]any)["status"] == "paused"
	})
	cb.waitFor("chat", func(d map[string]any) bool {
		return d["kind"] == "system" && strings.Contains(d["text"].(string), "同意了 bob 的请求")
	})

	if ack := cb.call("danmaku", map[string]string{"text": "哈哈哈"}); ack["ok"] != true {
		t.Fatalf("danmaku = %v", ack)
	}
	ca.waitFor("chat", func(d map[string]any) bool { return d["kind"] == "danmaku" && d["text"] == "哈哈哈" })

	// 服主把 bob 设成管理员，bob 就能直接控制，也能改档位。
	snapB, _ := a.Members.Authenticate(bob)
	if code := adminJSON(t, "POST", srv.URL+"/music/lt/members/"+snapB.ID+"/admin", map[string]bool{"admin": true}, nil); code != 200 {
		t.Fatalf("set admin status %d", code)
	}
	ca.waitFor("video", func(d map[string]any) bool {
		ids, _ := d["video"].(map[string]any)["adminIds"].([]any)
		return len(ids) == 1
	})
	if ack := cb.call("control", map[string]any{"action": "play", "seq": seq}); ack["d"].(map[string]any)["requested"] != false {
		t.Fatalf("admin play ack = %v", ack)
	}
	if ack := cb.call("set_policy", map[string]string{"policy": "admins"}); ack["ok"] != true {
		t.Fatalf("set_policy = %v", ack)
	}

	// 默认档位设成「管理员做主」后取消唯一的管理员，默认档位自动改回。
	var settings map[string]any
	adminJSON(t, "PUT", srv.URL+"/music/lt/video_settings", map[string]any{"policy": "admins", "locked": true}, &settings)
	if settings["policy"] != "admins" || settings["locked"] != true {
		t.Fatalf("settings = %v", settings)
	}
	var out map[string]any
	adminJSON(t, "POST", srv.URL+"/music/lt/members/"+snapB.ID+"/admin", map[string]bool{"admin": false}, &out)
	if out["policyReverted"] != true {
		t.Fatalf("revert = %v", out)
	}
	adminJSON(t, "GET", srv.URL+"/music/lt/video_settings", nil, &settings)
	if settings["policy"] != "requester" {
		t.Fatalf("settings after revert = %v", settings)
	}
}
