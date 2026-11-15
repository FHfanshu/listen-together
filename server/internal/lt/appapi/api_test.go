package appapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/gin-gonic/gin"
	"github.com/guohuiyuan/go-music-dl/internal/lt/identity"
	"github.com/guohuiyuan/music-lib/model"

	"github.com/guohuiyuan/go-music-dl/internal/lt/members"
	"github.com/guohuiyuan/go-music-dl/internal/lt/playurl"
	"github.com/guohuiyuan/go-music-dl/internal/lt/podcast"
)

func newTestServer(t *testing.T) (*httptest.Server, *API) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	store, err := members.Open(filepath.Join(t.TempDir(), "lt.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	a := New(store)
	if a.Identity, err = identity.Load(filepath.Join(t.TempDir(), "key.pem")); err != nil {
		t.Fatal(err)
	}
	a.Search = func(source, keyword string) ([]model.Song, error) {
		return []model.Song{
			{ID: "1", Source: source, Name: "晴天", Artist: "周杰伦", Duration: 240},
			{ID: "2", Source: source, Name: "七里香", Artist: "周杰伦", Duration: 300},
		}, nil
	}
	a.Resolver.Resolve = func(s *model.Song, q playurl.Quality) (string, error) {
		return "https://cdn.example/" + s.ID + ".mp3", nil
	}
	a.Resolver.Probe = func(url, source string, withCookie bool) (int64, error) { return 300 * 40_000, nil }

	r := gin.New()
	a.Mount(r.Group(Prefix))
	a.MountAdmin(r.Group("/music/lt"))
	srv := httptest.NewServer(r)
	t.Cleanup(srv.Close)
	return srv, a
}

func postJSON(t *testing.T, url string, body any, out any) int {
	t.Helper()
	b, _ := json.Marshal(body)
	resp, err := http.Post(url, "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func getJSON(t *testing.T, url, token string, out any) int {
	t.Helper()
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("Authorization", "Bearer "+token)
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

type wsClient struct {
	t    *testing.T
	ws   *websocket.Conn
	seq  int
	msgs chan map[string]any
}

func dial(t *testing.T, srv *httptest.Server, token, roomCode string) (*wsClient, map[string]any) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + Prefix + "/ws"
	ws, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	c := &wsClient{t: t, ws: ws, msgs: make(chan map[string]any, 256)}
	t.Cleanup(func() { _ = ws.Close(websocket.StatusNormalClosure, "") })
	go func() {
		for {
			_, data, err := ws.Read(context.Background())
			if err != nil {
				close(c.msgs)
				return
			}
			var m map[string]any
			_ = json.Unmarshal(data, &m)
			c.msgs <- m
		}
	}()
	hello := c.call("hello", map[string]any{"token": token, "roomCode": roomCode})
	return c, hello
}

// call 发送一条请求并等待对应 id 的 ack，期间收到的广播留在通道里。
func (c *wsClient) call(typ string, d any) map[string]any {
	c.t.Helper()
	c.seq++
	id := fmt.Sprint(c.seq)
	b, _ := json.Marshal(map[string]any{"t": typ, "id": id, "d": d})
	if err := c.ws.Write(context.Background(), websocket.MessageText, b); err != nil {
		c.t.Fatal(err)
	}
	var pending []map[string]any
	defer func() {
		for _, m := range pending {
			c.msgs <- m
		}
	}()
	for {
		m := c.next()
		if m["t"] == "ack" && m["id"] == id {
			return m
		}
		pending = append(pending, m)
	}
}

func (c *wsClient) next() map[string]any {
	c.t.Helper()
	select {
	case m, ok := <-c.msgs:
		if !ok {
			c.t.Fatal("connection closed")
		}
		return m
	case <-time.After(3 * time.Second):
		c.t.Fatal("timeout waiting for message")
		return nil
	}
}

// waitFor 丢弃其它消息，直到收到某类广播且满足条件。
func (c *wsClient) waitFor(typ string, ok func(d map[string]any) bool) map[string]any {
	c.t.Helper()
	for {
		m := c.next()
		if m["t"] != typ {
			continue
		}
		d, _ := m["d"].(map[string]any)
		if ok == nil || ok(d) {
			return d
		}
	}
}

func join(t *testing.T, srv *httptest.Server, a *API, nick string) string {
	t.Helper()
	inv, err := a.Members.CreateInvite(1, 0)
	if err != nil {
		t.Fatal(err)
	}
	var out struct{ Token string }
	if code := postJSON(t, srv.URL+Prefix+"/join", map[string]string{"inviteCode": strings.ToLower(inv.Code), "nickname": nick}, &out); code != 200 {
		t.Fatalf("join status %d", code)
	}
	return out.Token
}

func TestListenTogetherFlow(t *testing.T) {
	srv, a := newTestServer(t)
	alice := join(t, srv, a, "alice")
	bob := join(t, srv, a, "bob")

	if code := getJSON(t, srv.URL+Prefix+"/rooms", "bad", nil); code != 401 {
		t.Fatalf("bad token status %d", code)
	}
	var search struct {
		Tracks []struct{ ID, Title string }
	}
	getJSON(t, srv.URL+Prefix+"/search?q=周杰伦", alice, &search)
	if len(search.Tracks) != 2 {
		t.Fatalf("search = %+v", search)
	}

	var play struct {
		Direct *struct{ URL string }
		Proxy  string
	}
	getJSON(t, srv.URL+Prefix+"/play/netease/1", alice, &play)
	if play.Direct == nil || play.Direct.URL != "https://cdn.example/1.mp3" || play.Proxy != Prefix+"/stream/netease/1" {
		t.Fatalf("play = %+v", play)
	}

	ca, _ := dial(t, srv, alice, "")
	ts := ca.call("time_sync", map[string]any{"t0": 1})
	if d := ts["d"].(map[string]any); d["t1"] == nil || d["t2"] == nil {
		t.Fatalf("time_sync = %v", ts)
	}
	created := ca.call("create_room", nil)
	roomCode := created["d"].(map[string]any)["roomCode"].(string)
	ca.waitFor("snapshot", nil)

	cb, hello := dial(t, srv, bob, roomCode)
	if hello["d"].(map[string]any)["roomCode"] != roomCode {
		t.Fatalf("bob hello = %v", hello)
	}
	cb.waitFor("snapshot", nil)

	if ack := cb.call("add_song", map[string]string{"source": "netease", "id": "999"}); ack["ok"] == true {
		t.Fatal("unsearched song should be rejected")
	}
	if ack := ca.call("add_song", map[string]string{"source": "netease", "id": "1"}); ack["ok"] != true {
		t.Fatalf("add_song = %v", ack)
	}
	// 第一首立即进入准备态，两人都报 ready 后开播。
	pb := cb.waitFor("playback", func(d map[string]any) bool {
		p := d["playback"].(map[string]any)
		return p["status"] == "preparing"
	})
	seq := pb["playback"].(map[string]any)["seq"]
	ca.call("track_status", map[string]any{"seq": seq, "status": "ready"})
	cb.call("track_status", map[string]any{"seq": seq, "status": "ready"})
	cb.waitFor("playback", func(d map[string]any) bool {
		return d["playback"].(map[string]any)["status"] == "playing"
	})

	add := cb.call("add_song", map[string]string{"source": "netease", "id": "2"})
	itemID := add["d"].(map[string]any)["itemId"]
	if ack := cb.call("vote", map[string]any{"itemId": itemID, "on": true}); ack["ok"] != true {
		t.Fatalf("self vote = %v", ack)
	}
	if ack := ca.call("vote", map[string]any{"itemId": itemID, "on": true}); ack["ok"] != true {
		t.Fatalf("vote = %v", ack)
	}
	cb.waitFor("queue", func(d map[string]any) bool {
		q, _ := d["queue"].([]any)
		if len(q) != 1 {
			return false
		}
		voters, _ := q[0].(map[string]any)["voterIds"].([]any)
		return len(voters) == 2
	})

	// 两人都点跳过才切歌。
	ca.call("skip", map[string]any{"seq": seq, "on": true})
	cb.call("skip", map[string]any{"seq": seq, "on": true})
	cb.waitFor("notice", func(d map[string]any) bool { return d["kind"] == "vote_skip" })

	var rooms struct {
		Rooms []struct {
			RoomCode    string
			OnlineCount int
		}
	}
	getJSON(t, srv.URL+Prefix+"/rooms", alice, &rooms)
	if len(rooms.Rooms) != 1 || rooms.Rooms[0].OnlineCount != 2 {
		t.Fatalf("rooms = %+v", rooms)
	}

	// 停用成员后他被移出房间、连接断开、token 失效。
	ids, _ := a.Members.ListMembers()
	for _, m := range ids {
		if m.Nickname == "bob" {
			postJSON(t, srv.URL+"/music/lt/members/"+m.ID+"/disabled", map[string]bool{"disabled": true}, nil)
		}
	}
	if code := getJSON(t, srv.URL+Prefix+"/rooms", bob, nil); code != 401 {
		t.Fatalf("disabled bob status %d", code)
	}
	ca.waitFor("members", func(d map[string]any) bool {
		ms := d["members"].([]any)
		return len(ms) == 1 && ms[0].(map[string]any)["nickname"] == "alice"
	})
}

func TestHelloRejectsBadToken(t *testing.T) {
	srv, _ := newTestServer(t)
	_, hello := dial(t, srv, "nope", "")
	if hello["ok"] == true {
		t.Fatalf("hello = %v", hello)
	}
}

func TestProfileUpdateBroadcastsToRoom(t *testing.T) {
	srv, a := newTestServer(t)
	a.avatarDir = t.TempDir()
	alice := join(t, srv, a, "alice")
	ca, _ := dial(t, srv, alice, "")
	ca.call("create_room", nil)
	ca.waitFor("snapshot", nil)

	send := func(method, path, ctype string, body []byte) (int, map[string]any) {
		req, _ := http.NewRequest(method, srv.URL+Prefix+path, bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+alice)
		req.Header.Set("Content-Type", ctype)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return resp.StatusCode, out
	}

	if code, _ := send("PATCH", "/me", "application/json", []byte(`{"avatar":"preset:99"}`)); code != 400 {
		t.Fatalf("bad preset status %d", code)
	}
	if code, me := send("PATCH", "/me", "application/json", []byte(`{"nickname":"爱丽丝","avatar":"preset:3"}`)); code != 200 || me["avatar"] != "preset:3" {
		t.Fatalf("patch = %d %v", code, me)
	}
	ca.waitFor("members", func(d map[string]any) bool {
		m := d["members"].([]any)[0].(map[string]any)
		return m["nickname"] == "爱丽丝" && m["avatar"] == "preset:3"
	})

	if code, _ := send("PUT", "/me/avatar", "image/jpeg", []byte("not an image")); code != 400 {
		t.Fatalf("non-image upload status %d", code)
	}
	jpeg := append([]byte{0xFF, 0xD8, 0xFF, 0xE0}, make([]byte, 64)...)
	code, me := send("PUT", "/me/avatar", "image/jpeg", jpeg)
	if code != 200 || !strings.HasPrefix(me["avatar"].(string), "photo:") {
		t.Fatalf("upload = %d %v", code, me)
	}
	resp, err := http.Get(srv.URL + Prefix + "/avatars/" + me["userId"].(string))
	if err != nil || resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/jpeg" {
		t.Fatalf("avatar get = %v %v", resp, err)
	}
	resp.Body.Close()
}

func TestPodcastBrowseAndAdd(t *testing.T) {
	srv, a := newTestServer(t)
	a.Podcasts = &podcast.Client{Post: func(apiURL string, params map[string]any, cookie string) ([]byte, error) {
		switch {
		case strings.Contains(apiURL, "cloudsearch"):
			return []byte(`{"code":200,"result":{"djRadios":[{"id":7,"name":"晚安电台","programCount":2,"dj":{"nickname":"拾柒"}}]}}`), nil
		case strings.Contains(apiURL, "byradio"):
			if params["radioId"] != "7" || params["offset"] != 50 {
				return nil, fmt.Errorf("参数不对 %v", params)
			}
			return []byte(`{"code":200,"count":52,"more":false,"programs":[{"id":1,"name":"第一期","dj":{"nickname":"拾柒"},"mainSong":{"id":900,"duration":600000}}]}`), nil
		}
		return nil, fmt.Errorf("unexpected %s", apiURL)
	}}
	alice := join(t, srv, a, "alice")

	var pods struct{ Podcasts []podcast.Podcast }
	if code := getJSON(t, srv.URL+Prefix+"/podcasts?q=晚安", alice, &pods); code != 200 || len(pods.Podcasts) != 1 || pods.Podcasts[0].ID != "7" {
		t.Fatalf("podcasts %d %+v", code, pods)
	}
	var progs struct {
		Tracks     []struct{ Source, ID, Title string }
		Total      int
		HasMore    bool
		NextOffset int
	}
	if code := getJSON(t, srv.URL+Prefix+"/podcasts/7/programs?offset=50", alice, &progs); code != 200 {
		t.Fatalf("programs status %d", code)
	}
	if len(progs.Tracks) != 1 || progs.Tracks[0].Source != "netease" || progs.Tracks[0].ID != "900" || progs.Total != 52 || progs.HasMore {
		t.Fatalf("programs %+v", progs)
	}
	// 节目进了搜索缓存，可以直接点：
	s := a.song("netease", "900")
	if s.Name != "第一期" || s.Extra[playurl.VoiceExtra] != "1" {
		t.Fatalf("缓存里的节目 %+v", s)
	}
}

func TestInfoProvesServerIdentity(t *testing.T) {
	srv, a := newTestServer(t)
	nonce := []byte("a fresh random nonce 32 bytes!!!")
	resp, err := http.Get(srv.URL + Prefix + "/info?nonce=" + base64.RawURLEncoding.EncodeToString(nonce))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var info struct{ ServerID, PublicKey, Proof string }
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	pub, _ := base64.StdEncoding.DecodeString(info.PublicKey)
	sig, _ := base64.StdEncoding.DecodeString(info.Proof)
	if info.ServerID != a.Identity.ID() || identity.Fingerprint(pub) != info.ServerID {
		t.Fatalf("server id mismatch: %+v", info)
	}
	if !identity.Verify(pub, nonce, sig) {
		t.Fatal("proof does not verify")
	}
	bad, err := http.Get(srv.URL + Prefix + "/info?nonce=short")
	if err != nil {
		t.Fatal(err)
	}
	bad.Body.Close()
	if bad.StatusCode != 400 {
		t.Fatalf("short nonce: status %d, want 400", bad.StatusCode)
	}
}
