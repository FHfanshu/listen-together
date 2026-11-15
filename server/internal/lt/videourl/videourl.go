// Package videourl 解析放映厅用的 B 站 DASH 视频轨和音轨。
//
// 和听歌一样，用服务器上的 B 站 Cookie 换地址；换出来的 CDN 地址只要带 Referer 和 UA 就能下载，
// 所以默认交给手机直连，直连失败再走服务器代理（代理时 App 会主动要低一档画质，省服务器上行）。
package videourl

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/bilibili"
)

// 画质编号（B 站的 qn）。
const (
	Q360  = 16
	Q480  = 32
	Q720  = 64
	Q1080 = 80
	// DefaultQn：普通登录账号最高就是 1080P；更高的要大会员，成员自己选。
	DefaultQn = Q1080
	// ProxyQn 是走服务器代理时的画质上限。
	ProxyQn = Q720
)

// 编码偏好：AVC 兼容性最好，其次 HEVC、AV1。
const (
	codecAVC  = 7
	codecHEVC = 12
	codecAV1  = 13
)

const (
	cacheTTL = 20 * time.Minute
	// 地址里没有 deadline 时按这个算有效期。
	defaultLife = time.Hour
	// expirySlack：提前这么久当作过期，免得手机拿到马上失效的地址。
	expirySlack = 5 * time.Minute
)

var ErrBadID = errors.New("B 站视频 ID 格式不对")

// Stream 是一路可以直连的媒体地址。
type Stream struct {
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
}

type QualityOption struct {
	Qn    int    `json:"qn"`
	Label string `json:"label"`
}

// Result 是 /api/v1/video 的返回体，代理地址由接口层填写。
type Result struct {
	Video      Stream          `json:"video"`
	Audio      Stream          `json:"audio"`
	ProxyVideo string          `json:"proxyVideo"`
	ProxyAudio string          `json:"proxyAudio"`
	Qn         int             `json:"qn"`
	Qualities  []QualityOption `json:"qualities"`
	Width      int             `json:"width"`
	Height     int             `json:"height"`
	Codecs     string          `json:"codecs"`
	DurationMs int64           `json:"durationMs"`
	// ExpiresAt 之后地址可能失效，App 要带 refresh=1 重新拿。
	ExpiresAt int64 `json:"expiresAt"`
}

// ParseID 认 "BV号|cid"，和听歌里 B 站歌的 ID 一样。
func ParseID(id string) (bvid, cid string, err error) {
	parts := strings.Split(id, "|")
	if len(parts) != 2 || !strings.HasPrefix(parts[0], "BV") || parts[1] == "" {
		return "", "", ErrBadID
	}
	if _, err := strconv.ParseInt(parts[1], 10, 64); err != nil {
		return "", "", ErrBadID
	}
	return parts[0], parts[1], nil
}

type Resolver struct {
	Now func() time.Time
	// Fetch 请求 B 站 playurl 接口，返回响应体；测试里替换。
	Fetch func(bvid, cid string, qn int) ([]byte, error)

	mu    sync.Mutex
	cache map[string]cached
}

type cached struct {
	res Result
	at  time.Time
}

func New() *Resolver {
	return &Resolver{Now: time.Now, Fetch: fetchPlayURL, cache: map[string]cached{}}
}

// Lookup 解析指定画质（拿不到就取不超过它的最高画质）。refresh=true 忽略缓存。
func (r *Resolver) Lookup(id string, qn int, refresh bool) (Result, error) {
	bvid, cid, err := ParseID(id)
	if err != nil {
		return Result{}, err
	}
	if qn <= 0 {
		qn = DefaultQn
	}
	key := fmt.Sprintf("%s|%s|%d", bvid, cid, qn)
	now := r.Now()
	r.mu.Lock()
	c, ok := r.cache[key]
	r.mu.Unlock()
	if ok && !refresh && now.Sub(c.at) < cacheTTL && now.UnixMilli() < c.res.ExpiresAt {
		return c.res, nil
	}
	body, err := r.Fetch(bvid, cid, qn)
	if err != nil {
		return Result{}, err
	}
	res, err := parse(body, qn, now)
	if err != nil {
		return Result{}, err
	}
	r.mu.Lock()
	r.cache[key] = cached{res: res, at: now}
	if len(r.cache) > 500 {
		for k, v := range r.cache {
			if now.Sub(v.at) >= cacheTTL {
				delete(r.cache, k)
			}
		}
	}
	r.mu.Unlock()
	return res, nil
}

type dashVideo struct {
	ID        int    `json:"id"`
	BaseURL   string `json:"baseUrl"`
	Bandwidth int    `json:"bandwidth"`
	Codecs    string `json:"codecs"`
	CodecID   int    `json:"codecid"`
	Width     int    `json:"width"`
	Height    int    `json:"height"`
}

type dashAudio struct {
	ID      int    `json:"id"`
	BaseURL string `json:"baseUrl"`
}

type playURLResp struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    struct {
		Timelength        int64    `json:"timelength"`
		AcceptQuality     []int    `json:"accept_quality"`
		AcceptDescription []string `json:"accept_description"`
		Dash              *struct {
			Video []dashVideo `json:"video"`
			Audio []dashAudio `json:"audio"`
		} `json:"dash"`
	} `json:"data"`
}

func codecRank(id int) int {
	switch id {
	case codecAVC:
		return 0
	case codecHEVC:
		return 1
	case codecAV1:
		return 2
	}
	return 3
}

func parse(body []byte, wantQn int, now time.Time) (Result, error) {
	var resp playURLResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return Result{}, fmt.Errorf("B 站返回格式不对: %w", err)
	}
	if resp.Code != 0 {
		return Result{}, fmt.Errorf("B 站拒绝了请求: %d %s", resp.Code, resp.Message)
	}
	d := resp.Data
	if d.Dash == nil || len(d.Dash.Video) == 0 {
		return Result{}, errors.New("这个视频没有 DASH 视频轨")
	}

	// 先定画质：不超过 wantQn 的最高一档；都比它高就取最低的一档。
	qns := map[int]bool{}
	for _, v := range d.Dash.Video {
		if v.BaseURL != "" {
			qns[v.ID] = true
		}
	}
	chosenQn := -1
	lowest := 1 << 30
	for q := range qns {
		if q <= wantQn && q > chosenQn {
			chosenQn = q
		}
		lowest = min(lowest, q)
	}
	if chosenQn < 0 {
		chosenQn = lowest
	}
	var cands []dashVideo
	for _, v := range d.Dash.Video {
		if v.ID == chosenQn && v.BaseURL != "" {
			cands = append(cands, v)
		}
	}
	sort.SliceStable(cands, func(i, j int) bool { return codecRank(cands[i].CodecID) < codecRank(cands[j].CodecID) })
	video := cands[0]

	var audio dashAudio
	for _, a := range d.Dash.Audio {
		// 30280/30232/30216 是普通音轨，编号越大码率越高；杜比、Hi-Res 不在这个数组里。
		if a.BaseURL != "" && a.ID > audio.ID {
			audio = a
		}
	}
	if audio.BaseURL == "" {
		return Result{}, errors.New("这个视频没有音轨")
	}

	res := Result{
		Video:      Stream{URL: video.BaseURL, Headers: headers()},
		Audio:      Stream{URL: audio.BaseURL, Headers: headers()},
		Qn:         chosenQn,
		Width:      video.Width,
		Height:     video.Height,
		Codecs:     video.Codecs,
		DurationMs: d.Timelength,
		Qualities:  []QualityOption{},
	}
	for i, q := range d.AcceptQuality {
		label := strconv.Itoa(q)
		if i < len(d.AcceptDescription) {
			label = d.AcceptDescription[i]
		}
		res.Qualities = append(res.Qualities, QualityOption{Qn: q, Label: label})
	}
	exp := deadlineOf(video.BaseURL, now)
	if a := deadlineOf(audio.BaseURL, now); a.Before(exp) {
		exp = a
	}
	res.ExpiresAt = exp.Add(-expirySlack).UnixMilli()
	return res, nil
}

// deadlineOf 读 B 站 CDN 地址里的 deadline（Unix 秒）。
func deadlineOf(raw string, now time.Time) time.Time {
	u, err := url.Parse(raw)
	if err == nil {
		if s := u.Query().Get("deadline"); s != "" {
			if sec, err := strconv.ParseInt(s, 10, 64); err == nil && sec > now.Unix() {
				return time.Unix(sec, 0)
			}
		}
	}
	return now.Add(defaultLife)
}

// headers 是手机直连 CDN 要带的请求头，不带 Cookie。
func headers() map[string]string {
	return map[string]string{"User-Agent": bilibili.UserAgent, "Referer": bilibili.Referer}
}

var httpClient = &http.Client{Timeout: 15 * time.Second}

func fetchPlayURL(bvid, cid string, qn int) ([]byte, error) {
	// fnval=16 要 DASH；fourk=1 让 4K 也出现在可选画质里（有没有权限由账号决定）。
	apiURL := fmt.Sprintf("https://api.bilibili.com/x/player/playurl?fnval=16&fourk=1&qn=%d&bvid=%s&cid=%s", qn, bvid, cid)
	req, err := http.NewRequest(http.MethodGet, apiURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", bilibili.UserAgent)
	req.Header.Set("Referer", bilibili.Referer)
	req.Header.Set("Cookie", core.CM.Get("bilibili"))
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("B 站 HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 2<<20))
}
