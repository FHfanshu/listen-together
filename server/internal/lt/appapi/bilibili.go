package appapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/bilibili"
	"github.com/guohuiyuan/music-lib/model"
)

// B 站的关键词搜索接口要网页端签名，服务器拿不到结果；一起听里 B 站改成粘贴链接点歌：
// 认 App 分享出来的整段文案、视频链接、b23.tv 短链和 BV 号，列出这个视频的全部分P让成员挑。

var (
	errBilibiliNeedLink = errors.New("B 站不支持按关键词搜，请粘贴视频链接或 BV 号")
	bvidPattern         = regexp.MustCompile(`BV[0-9A-Za-z]{10}`)
	pagePattern         = regexp.MustCompile(`[?&]p=(\d+)`)
	urlPattern          = regexp.MustCompile(`https?://[^\s，。、」』）)】"']+`)
	// bilibiliMaxPages 防止几百P的课程视频一次把列表撑爆。
	bilibiliMaxPages = 300
)

// bilibiliRef 是从成员粘贴的内容里认出来的视频：BV 号和想听的分P（从 1 起，0 表示没指定）。
type bilibiliRef struct {
	BVID string
	Page int
}

// parseBilibiliRef 从粘贴的内容里找 BV 号和 p 参数；只有 b23.tv 短链时用 expand 换成长链再找。
func parseBilibiliRef(text string, expand func(string) (string, error)) (bilibiliRef, error) {
	text = strings.TrimSpace(text)
	if bv := bvidPattern.FindString(text); bv != "" {
		return bilibiliRef{BVID: bv, Page: pageOf(text)}, nil
	}
	for _, u := range urlPattern.FindAllString(text, -1) {
		if !strings.Contains(u, "b23.tv/") {
			continue
		}
		long, err := expand(u)
		if err != nil {
			return bilibiliRef{}, fmt.Errorf("B 站短链打不开: %w", err)
		}
		if bv := bvidPattern.FindString(long); bv != "" {
			return bilibiliRef{BVID: bv, Page: pageOf(long)}, nil
		}
		return bilibiliRef{}, errors.New("这个 B 站短链不是视频，暂时只支持视频")
	}
	return bilibiliRef{}, errBilibiliNeedLink
}

// looksLikeBilibili 判断粘贴的内容是不是 B 站视频：BV 号、视频链接或 b23.tv 短链。
func looksLikeBilibili(text string) bool {
	return bvidPattern.MatchString(text) || strings.Contains(text, "b23.tv/") || strings.Contains(text, "bilibili.com/video/")
}

func pageOf(s string) int {
	if m := pagePattern.FindStringSubmatch(s); m != nil {
		if p, err := strconv.Atoi(m[1]); err == nil && p > 0 {
			return p
		}
	}
	return 0
}

var b23Client = &http.Client{
	Timeout: 8 * time.Second,
	// 只要第一跳的 Location，不跟到 B 站页面上去。
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

func expandB23(short string) (string, error) {
	req, err := http.NewRequest(http.MethodGet, short, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", bilibili.UserAgent)
	resp, err := b23Client.Do(req)
	if err != nil {
		return "", err
	}
	resp.Body.Close()
	if loc := resp.Header.Get("Location"); loc != "" {
		return loc, nil
	}
	return "", fmt.Errorf("HTTP %d", resp.StatusCode)
}

// lookupBilibili 列出视频的全部分P。成员链接里带了 ?p= 就把那一P排到最前面。
func lookupBilibili(text string) ([]model.Song, error) {
	ref, err := parseBilibiliRef(text, expandB23)
	if err != nil {
		return nil, err
	}
	v, err := fetchBilibiliView(ref.BVID)
	if err != nil {
		return nil, fmt.Errorf("B 站视频解析失败: %w", err)
	}
	return bilibiliPageSongs(v, ref.Page), nil
}

type bilibiliView struct {
	BVID  string `json:"bvid"`
	Title string `json:"title"`
	Pic   string `json:"pic"`
	Owner struct {
		Name string `json:"name"`
	} `json:"owner"`
	Pages []struct {
		CID      int64  `json:"cid"`
		Part     string `json:"part"`
		Duration int    `json:"duration"`
	} `json:"pages"`
}

var bilibiliHTTP = &http.Client{Timeout: 10 * time.Second}

func fetchBilibiliView(bvid string) (*bilibiliView, error) {
	req, err := http.NewRequest(http.MethodGet, "https://api.bilibili.com/x/web-interface/view?bvid="+bvid, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", bilibili.UserAgent)
	req.Header.Set("Referer", bilibili.Referer)
	req.Header.Set("Cookie", core.CM.Get("bilibili"))
	resp, err := bilibiliHTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var body struct {
		Code    int          `json:"code"`
		Message string       `json:"message"`
		Data    bilibiliView `json:"data"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&body); err != nil {
		return nil, err
	}
	if body.Code != 0 {
		return nil, fmt.Errorf("%d %s", body.Code, body.Message)
	}
	if len(body.Data.Pages) == 0 {
		return nil, errors.New("视频没有可播放的分P")
	}
	return &body.Data, nil
}

// bilibiliPageSongs 把分P变成歌：单P用视频标题；多P用「P3 分P标题」，视频标题放进专辑。
// ID 和 Extra 跟 music-lib 一致（bvid|cid），播放地址照常走 playurl。
func bilibiliPageSongs(v *bilibiliView, page int) []model.Song {
	pages := v.Pages
	if len(pages) > bilibiliMaxPages {
		pages = pages[:bilibiliMaxPages]
	}
	cover := v.Pic
	if strings.HasPrefix(cover, "//") {
		cover = "https:" + cover
	} else if strings.HasPrefix(cover, "http://") {
		cover = "https://" + strings.TrimPrefix(cover, "http://")
	}
	songs := make([]model.Song, len(pages))
	for i, p := range pages {
		name := v.Title
		if len(pages) > 1 {
			part := strings.TrimSpace(p.Part)
			if part == "" {
				part = v.Title
			}
			name = fmt.Sprintf("P%d %s", i+1, part)
		}
		cid := strconv.FormatInt(p.CID, 10)
		songs[i] = model.Song{
			Source:   "bilibili",
			ID:       v.BVID + "|" + cid,
			Name:     name,
			Artist:   v.Owner.Name,
			Album:    v.Title,
			Duration: p.Duration,
			Cover:    cover,
			Link:     fmt.Sprintf("https://www.bilibili.com/video/%s?p=%d", v.BVID, i+1),
			Extra:    map[string]string{"bvid": v.BVID, "cid": cid},
		}
	}
	if page > 1 && page <= len(songs) {
		picked := songs[page-1]
		copy(songs[1:page], songs[:page-1])
		songs[0] = picked
	}
	return songs
}
