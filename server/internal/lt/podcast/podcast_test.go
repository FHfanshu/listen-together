package podcast

import (
	"testing"

	"github.com/guohuiyuan/go-music-dl/internal/lt/playurl"
)

func fake(t *testing.T, wantURL, body string) *Client {
	t.Helper()
	return &Client{
		Cookie: func() string { return "MUSIC_U=x" },
		Post: func(apiURL string, params map[string]any, cookie string) ([]byte, error) {
			if apiURL != wantURL {
				t.Fatalf("请求了 %s，期望 %s", apiURL, wantURL)
			}
			if cookie != "MUSIC_U=x" {
				t.Fatalf("没带上 cookie: %q", cookie)
			}
			return []byte(body), nil
		},
	}
}

func TestSearchVoicesUsesMainSong(t *testing.T) {
	c := fake(t, voiceSearchAPI, `{"code":200,"data":{"resources":[
		{"baseInfo":{"id":11,"name":" 晚安曲 ","coverUrl":"http://p1.music.126.net/a.jpg","duration":289184,
			"dj":{"nickname":"饺子"},"radio":{"id":5,"name":"睡前"},"mainSong":{"id":3077,"duration":289000}}},
		{"baseInfo":{"id":12,"name":"重复","mainSong":{"id":3077}}},
		{"baseInfo":{"id":13,"name":"没有音频"}}
	]}}`)
	songs, err := c.SearchVoices("晚安", 30)
	if err != nil {
		t.Fatal(err)
	}
	if len(songs) != 1 {
		t.Fatalf("应只剩 1 条（去重、去掉无音频），实际 %d", len(songs))
	}
	s := songs[0]
	if s.Source != "netease" || s.ID != "3077" || s.Name != "晚安曲" || s.Artist != "饺子" || s.Album != "睡前" {
		t.Fatalf("字段不对: %+v", s)
	}
	if s.Duration != 289 || s.Cover != "http://p1.music.126.net/a.jpg" {
		t.Fatalf("时长或封面不对: %+v", s)
	}
	if s.Extra[playurl.VoiceExtra] != "1" || s.Extra["program_id"] != "11" {
		t.Fatalf("Extra 不对: %+v", s.Extra)
	}
}

func TestSearchPodcasts(t *testing.T) {
	c := fake(t, podcastSearchAPI, `{"code":200,"result":{"djRadios":[
		{"id":794537646,"name":"晚安电台","picUrl":"http://p2.music.126.net/b.jpg","category":"情感","programCount":792,"dj":{"nickname":"拾柒"}}
	]}}`)
	list, err := c.SearchPodcasts("晚安", 30)
	if err != nil {
		t.Fatal(err)
	}
	want := Podcast{ID: "794537646", Name: "晚安电台", CoverURL: "http://p2.music.126.net/b.jpg", DJ: "拾柒", Category: "情感", ProgramCount: 792}
	if len(list) != 1 || list[0] != want {
		t.Fatalf("got %+v", list)
	}
}

func TestProgramsFallsBackToProgramDuration(t *testing.T) {
	c := fake(t, programsAPI, `{"code":200,"count":792,"more":true,"programs":[
		{"id":1,"name":"第一期","duration":930925,"dj":{"nickname":"拾柒"},"radio":{"name":"晚安电台"},"mainSong":{"id":3451141561}}
	]}`)
	songs, total, more, err := c.Programs("794537646", 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if total != 792 || !more || len(songs) != 1 || songs[0].ID != "3451141561" || songs[0].Duration != 930 {
		t.Fatalf("got %+v total=%d more=%v", songs, total, more)
	}
}

func TestErrorCode(t *testing.T) {
	c := fake(t, programsAPI, `{"code":404,"message":"电台不存在"}`)
	if _, _, _, err := c.Programs("1", 0, 50); err == nil {
		t.Fatal("非 200 应返回错误")
	}
}
