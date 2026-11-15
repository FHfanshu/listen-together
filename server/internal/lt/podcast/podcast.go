// Package podcast 接网易云音乐的播客（原"电台"）：搜单条声音、搜播客、列播客的节目。
//
// 每条声音背后都有一个普通的网易云歌曲 ID（mainSong），所以结果统一转成
// Source 为 netease 的 model.Song，取地址、代理、同步播放都沿用原有流程。
package podcast

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/guohuiyuan/music-lib/model"
	"github.com/guohuiyuan/music-lib/netease"

	"github.com/guohuiyuan/go-music-dl/internal/lt/playurl"
)

// 网页版 /weapi/cloudsearch 的结果现在是加密的，这里和 music-lib 一样走 linux 转发接口。
const (
	voiceSearchAPI   = "http://music.163.com/api/search/voice/get"
	podcastSearchAPI = "http://music.163.com/api/cloudsearch/pc"
	programsAPI      = "http://music.163.com/api/dj/program/byradio"
	podcastSearchTyp = 1009
)

type Podcast struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	CoverURL     string `json:"coverUrl,omitempty"`
	DJ           string `json:"dj,omitempty"`
	Category     string `json:"category,omitempty"`
	ProgramCount int    `json:"programCount"`
}

type Client struct {
	// Cookie 每次请求时取，扫码重新登录后立刻生效。
	Cookie func() string
	// Post 发一次 linux 转发请求并返回响应体，测试时替换。
	Post func(apiURL string, params map[string]any, cookie string) ([]byte, error)
}

func New(cookie func() string) *Client {
	return &Client{Cookie: cookie, Post: linuxPost}
}

var httpClient = &http.Client{Timeout: 15 * time.Second}

func linuxPost(apiURL string, params map[string]any, cookie string) ([]byte, error) {
	payload, _ := json.Marshal(map[string]any{"method": "POST", "url": apiURL, "params": params})
	form := url.Values{"eparams": {netease.EncryptLinux(string(payload))}}
	req, err := http.NewRequest("POST", netease.SearchAPI, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Referer", netease.Referer)
	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
	if cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("网易云返回 HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 8<<20))
}

func (c *Client) call(apiURL string, params map[string]any, out any) error {
	cookie := ""
	if c.Cookie != nil {
		cookie = c.Cookie()
	}
	body, err := c.Post(apiURL, params, cookie)
	if err != nil {
		return err
	}
	var head struct {
		Code int    `json:"code"`
		Msg  string `json:"message"`
	}
	if err := json.Unmarshal(body, &head); err != nil {
		return fmt.Errorf("网易云返回格式不对: %w", err)
	}
	if head.Code != 200 {
		return fmt.Errorf("网易云返回错误 %d %s", head.Code, head.Msg)
	}
	return json.Unmarshal(body, out)
}

type apiDJ struct {
	Nickname string `json:"nickname"`
}

type apiProgram struct {
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	CoverURL string `json:"coverUrl"`
	Duration int64  `json:"duration"`
	DJ       apiDJ  `json:"dj"`
	Radio    struct {
		ID   int64  `json:"id"`
		Name string `json:"name"`
	} `json:"radio"`
	MainSong *struct {
		ID       int64 `json:"id"`
		Duration int64 `json:"duration"`
	} `json:"mainSong"`
}

func (p apiProgram) song() (model.Song, bool) {
	if p.MainSong == nil || p.MainSong.ID == 0 {
		return model.Song{}, false
	}
	durMs := p.MainSong.Duration
	if durMs == 0 {
		durMs = p.Duration
	}
	return model.Song{
		Source:   "netease",
		ID:       strconv.FormatInt(p.MainSong.ID, 10),
		Name:     strings.TrimSpace(p.Name),
		Artist:   strings.TrimSpace(p.DJ.Nickname),
		Album:    strings.TrimSpace(p.Radio.Name),
		Duration: int(durMs / 1000),
		Cover:    p.CoverURL,
		Extra: map[string]string{
			playurl.VoiceExtra: "1",
			"program_id":       strconv.FormatInt(p.ID, 10),
		},
	}, true
}

// SearchVoices 搜单条声音（播客里的一期）。
func (c *Client) SearchVoices(keyword string, limit int) ([]model.Song, error) {
	var resp struct {
		Data struct {
			Resources []struct {
				BaseInfo apiProgram `json:"baseInfo"`
			} `json:"resources"`
		} `json:"data"`
	}
	err := c.call(voiceSearchAPI, map[string]any{"keyword": keyword, "scene": "normal", "limit": limit, "offset": 0}, &resp)
	if err != nil {
		return nil, err
	}
	out := make([]model.Song, 0, len(resp.Data.Resources))
	seen := map[string]bool{}
	for _, r := range resp.Data.Resources {
		if s, ok := r.BaseInfo.song(); ok && !seen[s.ID] {
			seen[s.ID] = true
			out = append(out, s)
		}
	}
	return out, nil
}

// SearchPodcasts 搜播客（以前叫电台）。
func (c *Client) SearchPodcasts(keyword string, limit int) ([]Podcast, error) {
	var resp struct {
		Result struct {
			DJRadios []struct {
				ID         int64  `json:"id"`
				Name       string `json:"name"`
				PicURL     string `json:"picUrl"`
				Category   string `json:"category"`
				ProgramCnt int    `json:"programCount"`
				DJ         apiDJ  `json:"dj"`
			} `json:"djRadios"`
		} `json:"result"`
	}
	err := c.call(podcastSearchAPI, map[string]any{"s": keyword, "type": podcastSearchTyp, "limit": limit, "offset": 0}, &resp)
	if err != nil {
		return nil, err
	}
	out := make([]Podcast, 0, len(resp.Result.DJRadios))
	for _, r := range resp.Result.DJRadios {
		out = append(out, Podcast{
			ID:           strconv.FormatInt(r.ID, 10),
			Name:         strings.TrimSpace(r.Name),
			CoverURL:     r.PicURL,
			DJ:           strings.TrimSpace(r.DJ.Nickname),
			Category:     r.Category,
			ProgramCount: r.ProgramCnt,
		})
	}
	return out, nil
}

// Programs 按从新到旧列出播客的节目，total 为节目总数。
func (c *Client) Programs(podcastID string, offset, limit int) (songs []model.Song, total int, hasMore bool, err error) {
	var resp struct {
		Programs []apiProgram `json:"programs"`
		Count    int          `json:"count"`
		More     bool         `json:"more"`
	}
	err = c.call(programsAPI, map[string]any{"radioId": podcastID, "limit": limit, "offset": offset, "asc": false}, &resp)
	if err != nil {
		return nil, 0, false, err
	}
	songs = make([]model.Song, 0, len(resp.Programs))
	for _, p := range resp.Programs {
		if s, ok := p.song(); ok {
			songs = append(songs, s)
		}
	}
	return songs, resp.Count, resp.More, nil
}
