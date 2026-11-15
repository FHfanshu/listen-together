// Package playurl 用服务器上的会员账号解析播放地址，决定成员手机是直连 CDN 还是走服务器代理。
//
// 会员权限只在解析时检查：解析出的 CDN 地址不带 Cookie 也能下载完整音频（OpenMusic 正常播放即如此）。
// 所以默认把地址直接交给手机，只有直连失败的歌或平台才回退到代理。
package playurl

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/model"
)

type Mode string

const (
	ModeDirectFirst Mode = "direct_first"
	ModeProxyOnly   Mode = "proxy_only"
	ModeDirectOnly  Mode = "direct_only"
)

const (
	defaultTTL = 10 * time.Minute
	// 低于 8KB/s（64kbps）视为试听片段：30 秒的试听文件按整首时长折算远低于此值。
	minBytesPerSecond = 8000
	// VoiceExtra 为 "1" 的歌是网易云播客声音，跳过试听检测。
	VoiceExtra = "voice"
	// 同一平台 10 分钟内直连失败 3 首，整个平台改走代理 1 小时。
	platformFailWindow    = 10 * time.Minute
	platformFailThreshold = 3
	platformProxyDuration = time.Hour
)

var ErrUnplayable = errors.New("unplayable")

// Quality 是成员自己选的播放音质。默认 320k；选了无损的成员单独解析无损地址，
// 拿不到（没会员、这首没有无损、平台不支持）就自动退回 320k，并在 Result.Quality 里如实告诉 App。
type Quality string

const (
	QualityStandard Quality = "standard"
	QualityLossless Quality = "lossless"
)

// ParseQuality 把请求参数转成音质，不认识的一律当默认。
func ParseQuality(s string) Quality {
	if Quality(strings.ToLower(strings.TrimSpace(s))) == QualityLossless {
		return QualityLossless
	}
	return QualityStandard
}

// StatusError 表示 CDN 明确拒绝了请求，区别于超时等网络问题。
type StatusError struct{ Code int }

func (e *StatusError) Error() string { return fmt.Sprintf("HTTP %d", e.Code) }

type Direct struct {
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	ExpiresAt int64             `json:"expiresAt"`
}

// Result 是 /api/v1/play 的返回体。Proxy 由接口层填写。
type Result struct {
	Direct     *Direct `json:"direct"`
	Proxy      string  `json:"proxy"`
	DurationMs int64   `json:"durationMs"`
	// Quality 是实际拿到的音质：请求无损但只拿到 320k 时这里是 standard。
	Quality Quality `json:"quality"`
}

type entry struct {
	url        string
	total      int64
	directOK   bool
	lossless   bool
	resolvedAt time.Time
}

type Resolver struct {
	Mode Mode
	TTL  time.Duration
	Now  func() time.Time
	// Resolve 用服务器上的 Cookie 换取指定音质的下载地址；默认走 core.GetDownloadFunc。
	// 实际文件格式写回 song.Ext（flac 等），用来判断拿到的是不是无损。
	Resolve func(song *model.Song, q Quality) (string, error)
	// Probe 请求前两个字节，返回文件总大小。withCookie=false 模拟成员手机直连。
	Probe func(url, source string, withCookie bool) (int64, error)

	mu                 sync.Mutex
	cache              map[string]entry
	directFailed       map[string]bool
	platformFails      map[string][]time.Time
	platformProxyUntil map[string]time.Time
}

func New() *Resolver {
	return &Resolver{
		Mode:    ModeDirectFirst,
		TTL:     defaultTTL,
		Now:     time.Now,
		Resolve: resolveWithCore,
		Probe:   probeWithCore,
	}
}

func KeyOf(source, id string) string { return source + ":" + id }

// Lookup 用默认音质解析一首歌并决定播放方式。refresh=true 时忽略缓存（手机发现地址过期时用）。
func (r *Resolver) Lookup(song *model.Song, refresh bool) (Result, error) {
	return r.LookupQuality(song, refresh, QualityStandard)
}

// LookupQuality 按成员选的音质解析；无损拿不到时退回默认音质。
func (r *Resolver) LookupQuality(song *model.Song, refresh bool, q Quality) (Result, error) {
	key := KeyOf(song.Source, song.ID)
	e, err := r.entryWithFallback(song, refresh, q)
	if err != nil {
		return Result{}, err
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	res := Result{DurationMs: int64(song.Duration) * 1000, Quality: QualityStandard}
	if e.lossless {
		res.Quality = QualityLossless
	}
	if r.directAllowedLocked(key, song.Source, e) {
		res.Direct = &Direct{
			URL:       e.url,
			Headers:   DirectHeaders(e.url, song.Source),
			ExpiresAt: e.resolvedAt.Add(r.TTL).UnixMilli(),
		}
	} else if r.Mode == ModeDirectOnly {
		return Result{}, fmt.Errorf("%w: 直连不可用且代理已关闭", ErrUnplayable)
	}
	return res, nil
}

// StreamURL 给代理接口用：返回服务器侧可下载的地址。
func (r *Resolver) StreamURL(song *model.Song, q Quality) (string, error) {
	e, err := r.entryWithFallback(song, false, q)
	if err != nil {
		return "", err
	}
	return e.url, nil
}

func (r *Resolver) entryWithFallback(song *model.Song, refresh bool, q Quality) (entry, error) {
	if q == QualityLossless {
		if e, err := r.entryFor(song, refresh, QualityLossless); err == nil {
			return e, nil
		}
	}
	return r.entryFor(song, refresh, QualityStandard)
}

// MarkDirectFailed 记录某首歌直连失败；之后对它只给代理地址。
func (r *Resolver) MarkDirectFailed(source, id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.directFailed == nil {
		r.directFailed = map[string]bool{}
		r.platformFails = map[string][]time.Time{}
		r.platformProxyUntil = map[string]time.Time{}
	}
	key := KeyOf(source, id)
	if r.directFailed[key] {
		return
	}
	r.directFailed[key] = true

	now := r.Now()
	recent := r.platformFails[source][:0]
	for _, t := range r.platformFails[source] {
		if now.Sub(t) < platformFailWindow {
			recent = append(recent, t)
		}
	}
	recent = append(recent, now)
	r.platformFails[source] = recent
	if len(recent) >= platformFailThreshold {
		r.platformProxyUntil[source] = now.Add(platformProxyDuration)
	}
}

func (r *Resolver) entryFor(song *model.Song, refresh bool, q Quality) (entry, error) {
	key := KeyOf(song.Source, song.ID) + "#" + string(q)
	r.mu.Lock()
	if r.cache == nil {
		r.cache = map[string]entry{}
	}
	e, ok := r.cache[key]
	r.mu.Unlock()
	if ok && !refresh && r.Now().Sub(e.resolvedAt) < r.TTL {
		return e, nil
	}

	// 解析和探测都是网络请求，不持锁。
	cp := *song
	url, err := r.Resolve(&cp, q)
	if err != nil || url == "" {
		return entry{}, fmt.Errorf("%w: 解析失败: %v", ErrUnplayable, err)
	}
	total, probeErr := r.Probe(url, song.Source, false)
	if probeErr != nil {
		// 跨境访问 CDN 时偶尔会超时，重试一次，免得一次抖动就把整首歌降级成代理。
		total, probeErr = r.Probe(url, song.Source, false)
	}
	directOK := probeErr == nil
	if !directOK {
		cookieErr := error(nil)
		total, cookieErr = r.Probe(url, song.Source, true)
		if cookieErr != nil {
			// 服务器在海外时，部分国内 CDN 节点会不响应，但成员手机可能访问得到。
			// 只要平台没有明确拒绝（HTTP 4xx/5xx），就把地址交给手机去试，不做试听检测。
			var se *StatusError
			if errors.As(probeErr, &se) || errors.As(cookieErr, &se) {
				return entry{}, fmt.Errorf("%w: 地址不可下载: %v", ErrUnplayable, cookieErr)
			}
			directOK, total = true, 0
		}
	}
	// 播客声音常用 48kbps 这类低码率，不按音乐的标准判断试听片段；B 站没有试听片段，老视频的音轨却可能只有 64k。
	isVoice := song.Extra != nil && song.Extra[VoiceExtra] == "1"
	if !isVoice && song.Source != "bilibili" && song.Duration >= 60 && total > 0 && total/int64(song.Duration) < minBytesPerSecond {
		return entry{}, fmt.Errorf("%w: 只有试听片段，解析账号可能没有会员", ErrUnplayable)
	}

	e = entry{url: url, total: total, directOK: directOK, lossless: isLosslessFile(cp.Ext, url), resolvedAt: r.Now()}
	r.mu.Lock()
	r.cache[key] = e
	r.mu.Unlock()
	return e, nil
}

func (r *Resolver) directAllowedLocked(key, source string, e entry) bool {
	if r.Mode == ModeProxyOnly || !e.directOK || r.directFailed[key] {
		return false
	}
	if until, ok := r.platformProxyUntil[source]; ok && r.Now().Before(until) {
		return false
	}
	return true
}

// isLosslessFile 按解析结果的格式判断是不是无损；没写格式就看地址的扩展名。
func isLosslessFile(ext, url string) bool {
	ext = strings.ToLower(strings.TrimPrefix(strings.TrimSpace(ext), "."))
	if ext == "" {
		path := strings.ToLower(url)
		if i := strings.IndexAny(path, "?#"); i >= 0 {
			path = path[:i]
		}
		if i := strings.LastIndex(path, "."); i >= 0 {
			ext = path[i+1:]
		}
	}
	switch ext {
	case "flac", "ape", "wav", "alac":
		return true
	}
	return false
}

// DirectHeaders 是手机直连时要带的请求头：与服务器下载时一致，但去掉 Cookie。
func DirectHeaders(url, source string) map[string]string {
	req, err := core.BuildSourceRequest(http.MethodGet, url, source, "")
	if err != nil {
		return map[string]string{}
	}
	out := map[string]string{}
	for k := range req.Header {
		if strings.EqualFold(k, "Cookie") {
			continue
		}
		out[k] = req.Header.Get(k)
	}
	return out
}

// streamLevels 是一起听播放用的音质。默认 320kbps：无损/Hi-Res 一首上百 MB，手机边下边播容易卡；
// 成员自己选了无损才要无损（不要 Hi-Res，体积再翻倍，手机上听不出差别）。
var streamLevels = map[Quality]map[string]map[string]string{
	QualityStandard: {"netease": {"netease_level": "exhigh"}},
	QualityLossless: {"netease": {"netease_level": "lossless"}},
}

func resolveWithCore(song *model.Song, q Quality) (string, error) {
	if extra, ok := streamLevels[q][song.Source]; ok {
		merged := make(map[string]string, len(song.Extra)+len(extra))
		for k, v := range song.Extra {
			merged[k] = v
		}
		for k, v := range extra {
			merged[k] = v
		}
		song.Extra = merged // song 是 entryFor 里的副本，不影响搜索缓存
	}
	switch song.Source {
	case "qq":
		return qqStreamURL(song, q == QualityLossless)
	case "bilibili":
		return bilibiliStreamURL(song, q == QualityLossless)
	case "kugou":
		song.Extra = kugouStreamExtra(song.Extra, q == QualityLossless)
	}
	fn := core.GetDownloadFunc(song.Source)
	if fn == nil {
		return "", fmt.Errorf("不支持的音源 %s", song.Source)
	}
	return fn(song)
}

func probeWithCore(url, source string, withCookie bool) (int64, error) {
	req, err := core.BuildSourceRequest(http.MethodGet, url, source, "bytes=0-1")
	if err != nil {
		return 0, err
	}
	if !withCookie {
		req.Header.Del("Cookie")
	}
	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusPartialContent:
		cr := resp.Header.Get("Content-Range")
		if i := strings.LastIndex(cr, "/"); i >= 0 {
			if n, err := strconv.ParseInt(cr[i+1:], 10, 64); err == nil {
				return n, nil
			}
		}
		return 0, nil
	case http.StatusOK:
		return resp.ContentLength, nil
	default:
		return 0, &StatusError{Code: resp.StatusCode}
	}
}
