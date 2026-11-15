package appapi

import (
	"encoding/json"
	"errors"
	"net/url"
	"testing"

	"github.com/guohuiyuan/music-lib/model"
)

func TestParseYouTubeID(t *testing.T) {
	cases := map[string]string{
		"https://www.youtube.com/watch?v=dQw4w9WgXcQ":                    "dQw4w9WgXcQ",
		"https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=30": "dQw4w9WgXcQ",
		"https://youtu.be/dQw4w9WgXcQ?si=abc":                            "dQw4w9WgXcQ",
		"看这个 https://youtube.com/shorts/aBcD_eF-123?feature=share 笑死":    "aBcD_eF-123",
		"https://m.youtube.com/watch?v=dQw4w9WgXcQ":                      "dQw4w9WgXcQ",
		"https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD":          "dQw4w9WgXcQ",
		"https://www.youtube.com/live/dQw4w9WgXcQ":                       "dQw4w9WgXcQ",
		"https://www.youtube.com/embed/dQw4w9WgXcQ":                      "dQw4w9WgXcQ",
		"周杰伦 晴天":                                      "",
		"https://www.youtube.com/@RickAstleyYT":       "",
		"https://www.bilibili.com/video/BV1GJ411x7h7": "",
	}
	for in, want := range cases {
		if got := parseYouTubeID(in); got != want {
			t.Errorf("%q: got %q want %q", in, got, want)
		}
		if looksLikeYouTube(in) != (want != "") {
			t.Errorf("looksLikeYouTube(%q) wrong", in)
		}
	}
}

func TestParseClock(t *testing.T) {
	for in, want := range map[string]int{"5:19": 319, "1:02:03": 3723, "0:07": 7, "": 0, "直播": 0} {
		if got := parseClock(in); got != want {
			t.Errorf("%q: %d want %d", in, got, want)
		}
	}
}

const ytSearchFixture = `{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
 {"itemSectionRenderer":{"contents":[
  {"videoRenderer":{"videoId":"DYptgVvkVLQ","title":{"runs":[{"text":"周杰倫 晴天"}]},"lengthText":{"simpleText":"5:19"},"ownerText":{"runs":[{"text":"周杰倫 Jay Chou"}]}}},
  {"shelfRenderer":{"title":{"simpleText":"相关"}}},
  {"videoRenderer":{"videoId":"liveliveliv","title":{"runs":[{"text":"直播中"}]},"ownerText":{"runs":[{"text":"某台"}]}}},
  {"videoRenderer":{"videoId":"v10bFxUDZsA","title":{"runs":[{"text":"晴天"},{"text":"【歌詞】"}]},"lengthText":{"simpleText":"4:29"},"ownerText":{"runs":[{"text":"Yuna"}]}}},
  {"videoRenderer":{"videoId":"DYptgVvkVLQ","title":{"runs":[{"text":"重复"}]},"lengthText":{"simpleText":"5:19"}}}
 ]}},
 {"continuationItemRenderer":{}}
]}}}}}`

func TestParseYouTubeSearch(t *testing.T) {
	songs, err := parseYouTubeSearch([]byte(ytSearchFixture))
	if err != nil {
		t.Fatal(err)
	}
	if len(songs) != 2 {
		t.Fatalf("songs = %+v", songs)
	}
	s := songs[0]
	if s.ID != "DYptgVvkVLQ" || s.Source != YouTubeSource || s.Duration != 319 || s.Artist != "周杰倫 Jay Chou" || s.Cover == "" {
		t.Errorf("first = %+v", s)
	}
	if songs[1].Name != "晴天【歌詞】" {
		t.Errorf("runs 拼接 = %q", songs[1].Name)
	}
}

func fakeYouTube(t *testing.T, player string) *youtubeClient {
	return &youtubeClient{post: func(endpoint string, body any) ([]byte, error) {
		switch endpoint {
		case "player":
			return []byte(player), nil
		case "search":
			return []byte(ytSearchFixture), nil
		}
		t.Fatalf("unexpected endpoint %s", endpoint)
		return nil, nil
	}}
}

func TestYouTubeLookup(t *testing.T) {
	// 服务器这边播放状态常是 UNPLAYABLE，只看 videoDetails。
	y := fakeYouTube(t, `{"playabilityStatus":{"status":"UNPLAYABLE"},"videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna","author":"Rick","lengthSeconds":"213"}}`)
	songs, err := y.lookup("https://youtu.be/dQw4w9WgXcQ")
	if err != nil || len(songs) != 1 || songs[0].Duration != 213 || songs[0].Name != "Never Gonna" {
		t.Fatalf("link lookup = %+v, %v", songs, err)
	}
	songs, err = y.lookup("周杰伦 晴天")
	if err != nil || len(songs) != 2 {
		t.Fatalf("keyword lookup = %+v, %v", songs, err)
	}
	if _, err := y.lookup("https://www.youtube.com/@someone"); !errors.Is(err, errYouTubeNotVideo) {
		t.Errorf("channel link err = %v", err)
	}

	live := fakeYouTube(t, `{"videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Live","lengthSeconds":"0","isLive":true}}`)
	if _, err := live.lookup("https://youtu.be/dQw4w9WgXcQ"); !errors.Is(err, errYouTubeLive) {
		t.Errorf("live err = %v", err)
	}
	gone := fakeYouTube(t, `{"playabilityStatus":{"status":"ERROR","reason":"Video unavailable"}}`)
	if _, err := gone.lookup("https://youtu.be/dQw4w9WgXcQ"); err == nil {
		t.Error("unavailable video should error")
	}
}

// 放映厅能点 YouTube；听歌房间不行；在别的标签粘 YouTube 链接也按 YouTube 解析。
func TestYouTubeInRooms(t *testing.T) {
	srv, a := newTestServer(t)
	a.YouTube = fakeYouTube(t, `{"videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna","author":"Rick","lengthSeconds":"213"}}`)
	music := a.Search
	a.Search = func(source, keyword string) ([]model.Song, error) {
		if source == YouTubeSource {
			return a.YouTube.lookup(keyword)
		}
		return music(source, keyword)
	}
	alice := join(t, srv, a, "alice")

	var search struct {
		Tracks []struct {
			Source, ID string
			DurationMs int64
		}
	}
	getJSON(t, srv.URL+Prefix+"/search?source=netease&q="+url.QueryEscape("https://youtu.be/dQw4w9WgXcQ"), alice, &search)
	if len(search.Tracks) != 1 || search.Tracks[0].Source != YouTubeSource || search.Tracks[0].DurationMs != 213_000 {
		raw, _ := json.Marshal(search)
		t.Fatalf("search = %s", raw)
	}

	ca, _ := dial(t, srv, alice, "")
	ca.call("create_room", map[string]string{"kind": "music"})
	ca.waitFor("snapshot", nil)
	if ack := ca.call("add_song", map[string]string{"source": YouTubeSource, "id": "dQw4w9WgXcQ"}); ack["ok"] == true {
		t.Fatal("music room should reject youtube")
	}
	ca.call("leave_room", nil)

	cv, _ := dial(t, srv, alice, "")
	cv.call("create_room", map[string]string{"kind": "video"})
	cv.waitFor("snapshot", nil)
	if ack := cv.call("add_song", map[string]string{"source": YouTubeSource, "id": "aaaaaaaaaaa"}); ack["ok"] == true {
		t.Fatal("unsearched youtube id should be rejected")
	}
	if ack := cv.call("add_song", map[string]string{"source": YouTubeSource, "id": "dQw4w9WgXcQ"}); ack["ok"] != true {
		t.Fatalf("add youtube = %v", ack)
	}
	cv.waitFor("playback", func(d map[string]any) bool {
		item, _ := d["playback"].(map[string]any)["item"].(map[string]any)
		return item != nil && item["track"].(map[string]any)["source"] == YouTubeSource
	})
}
