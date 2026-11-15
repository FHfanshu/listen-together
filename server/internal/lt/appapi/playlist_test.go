package appapi

import (
	"errors"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/guohuiyuan/music-lib/model"
)

func TestPlaylistLinks(t *testing.T) {
	for _, test := range []struct{ link, source, id string }{
		{"https://music.163.com/#/playlist?id=123&userid=456", "netease", "123"},
		{"https://music.163.com/playlist?id=123", "netease", "123"},
		{"https://y.music.163.com/m/playlist?id=123", "netease", "123"},
		{"https://music.163.com/playlist/123/456/?userid=789", "netease", "123"},
		{"https://y.qq.com/n/ryqq/playlist/123", "qq", "123"},
		{"https://y.qq.com/n/yqq/playlist/123.html", "qq", "123"},
		{"https://i.y.qq.com/n2/m/share/details/taoge.html?id=123", "qq", "123"},
		{"https://i2.y.qq.com/n3/other/pages/details/playlist.html?disstid=123", "qq", "123"},
	} {
		u, _ := url.Parse(test.link)
		got, ok := playlistLink(u)
		if !ok || got.Source != test.source || got.ID != test.id || strings.Contains(got.URL, "userid") {
			t.Errorf("%s => %+v, %v", test.link, got, ok)
		}
	}
	for _, link := range []string{"https://music.163.com.evil.test/playlist?id=123", "https://y.qq.com@127.0.0.1/playlist?id=123", "http://127.0.0.1/playlist?id=123", "https://y.qq.com:8080/n/ryqq/playlist/123", "https://y.qq.com/n/ryqq/songDetail/123", "https://music.163.com/playlist?id=abc", "https://music.163.com/playlist?id=0"} {
		u, _ := url.Parse(link)
		if _, ok := playlistLink(u); ok {
			t.Errorf("accepted invalid link %s", link)
		}
	}
}

// Opt-in: public metadata only, using an isolated test server and temporary member DB.
func TestPublicPlaylistLive(t *testing.T) {
	if os.Getenv("LT_PLAYLIST_LIVE_TEST") != "1" {
		t.Skip("set LT_PLAYLIST_LIVE_TEST=1 for public platform requests")
	}
	// Use one server/member pair so the HTTP authentication path is exercised too.
	srv, a := newTestServer(t)
	token := join(t, srv, a, "playlist-probe")
	for _, link := range []string{"https://music.163.com/playlist?id=3778678", "https://y.qq.com/n/ryqq/playlist/734191243"} {
		var got playlistPreview
		if status := getJSON(t, srv.URL+Prefix+"/playlists/resolve?url="+url.QueryEscape(link), token, &got); status != 200 {
			t.Fatalf("%s status=%d", link, status)
		}
		if len(got.Tracks) == 0 || !got.Complete {
			t.Fatalf("incomplete public sample: %s %d/%d", got.Name, len(got.Tracks), got.Total)
		}
		t.Logf("%s %s: %d/%d tracks", got.Source, got.Name, len(got.Tracks), got.Total)
	}
}

func TestPublicPlaylistAPI(t *testing.T) {
	srv, a := newTestServer(t)
	token := join(t, srv, a, "listener")
	called := 0
	a.Playlists = func(source, link string) (*model.Playlist, []model.Song, error) {
		called++
		if source != "netease" || link != "https://music.163.com/playlist?id=123" {
			t.Errorf("unexpected upstream input %s %s", source, link)
			return nil, nil, errors.New("unexpected upstream input")
		}
		return &model.Playlist{Name: "Test", TrackCount: 3}, []model.Song{
			{Source: source, ID: "2", Name: "Second", Duration: 200},
			{Source: source, ID: "1", Name: "First", Duration: 300},
		}, nil
	}
	endpoint := srv.URL + Prefix + "/playlists/resolve?url=" + url.QueryEscape("分享歌单《Test》 https://music.163.com/#/playlist?id=123&userid=456）")
	var preview playlistPreview
	if status := getJSON(t, endpoint, "", nil); status != 401 || called != 0 {
		t.Fatalf("unauthenticated import status=%d calls=%d", status, called)
	}
	if status := getJSON(t, endpoint, token, &preview); status != 200 {
		t.Fatalf("status=%d", status)
	}
	if preview.Complete || preview.Total != 3 || len(preview.Tracks) != 2 || preview.Tracks[0].ID != "2" || preview.Tracks[1].ID != "1" {
		t.Fatalf("partial results/order lost: %+v", preview)
	}
	if a.song("netease", "2").Name != "Second" {
		t.Fatal("imported song metadata was not cached for room playback")
	}
	client, _ := dial(t, srv, token, "")
	client.call("create_room", nil)
	client.waitFor("snapshot", nil)
	if ack := client.call("add_song", map[string]string{"source": "netease", "id": "2"}); ack["ok"] != true {
		t.Fatalf("imported song cannot be queued: %v", ack)
	}
	for _, test := range []struct {
		title                string
		count, songs, status int
		fails                bool
	}{
		{"complete", 2, 2, 200, false}, {"empty or private", 0, 0, 422, false}, {"unavailable", 2, 0, 502, true},
	} {
		t.Run(test.title, func(t *testing.T) {
			a.Playlists = func(string, string) (*model.Playlist, []model.Song, error) {
				if test.fails {
					return nil, nil, errors.New("upstream error")
				}
				var songs []model.Song
				for i := 0; i < test.songs; i++ {
					songs = append(songs, model.Song{Source: "netease", ID: "1", Name: "Test"})
				}
				return &model.Playlist{Name: test.title, TrackCount: test.count}, songs, nil
			}
			var got playlistPreview
			if status := getJSON(t, endpoint, token, &got); status != test.status {
				t.Fatalf("status=%d", status)
			}
			if test.status == 200 && !got.Complete {
				t.Fatal("complete import reported as partial")
			}
		})
	}
	a.Playlists = func(string, string) (*model.Playlist, []model.Song, error) {
		t.Fatal("invalid URL reached provider")
		return nil, nil, nil
	}
	for _, link := range []string{"https://127.0.0.1/playlist?id=123", "https://evil.test/playlist?id=123", "https://music.163.com/song?id=123", strings.Repeat("a", 8193)} {
		if status := getJSON(t, srv.URL+Prefix+"/playlists/resolve?url="+url.QueryEscape(link), token, nil); status != 400 {
			t.Errorf("%s status=%d", link[:min(len(link), 90)], status)
		}
	}
}
