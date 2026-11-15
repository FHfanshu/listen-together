package playurl

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"strings"
	"time"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/bilibili"
	"github.com/guohuiyuan/music-lib/model"
	"github.com/guohuiyuan/music-lib/qq"
)

// 一起听默认用 320kbps：会员账号默认会拿到无损甚至母带，一首上百 MB，手机边下边播容易卡。
// 成员自己选了无损时才要无损。music-lib 的 QQ、酷狗下载函数没有音质参数，这里按平台各自处理。

// kugouStreamExtra 控制酷狗拿哪个音质：music-lib 按 hash、sq_hash（无损）、hq_hash（320k）… 的顺序尝试。
// 默认把 hash 换成 hq_hash 并去掉无损、Hi-Res 的 hash，就会先拿 320k，没有 320k 时退回原始 hash；
// 要无损时把 hash 换成 sq_hash，没有无损就按默认处理。Hi-Res（res_hash）两种情况都不要。
func kugouStreamExtra(extra map[string]string, lossless bool) map[string]string {
	out := make(map[string]string, len(extra))
	for k, v := range extra {
		out[k] = v
	}
	delete(out, "res_hash")
	if sq := strings.TrimSpace(out["sq_hash"]); lossless && sq != "" {
		out["hash"] = sq
		return out
	}
	if hq := strings.TrimSpace(out["hq_hash"]); hq != "" {
		out["hash"] = hq
	}
	delete(out, "sq_hash")
	return out
}

var qqHTTP = &http.Client{Timeout: 15 * time.Second}

// qqStreamURL 向 QQ 音乐请求 320k（M800），拿不到再要 128k（M500）；要无损时先试 FLAC（F000，需要会员）。
func qqStreamURL(song *model.Song, lossless bool) (string, error) {
	mid := song.ID
	if song.Extra != nil && song.Extra["songmid"] != "" {
		mid = song.Extra["songmid"]
	}
	filenames := []string{"M800" + mid + mid + ".mp3", "M500" + mid + mid + ".mp3"}
	if lossless {
		filenames = append([]string{"F000" + mid + mid + ".flac"}, filenames...)
	}
	guid := fmt.Sprintf("%d", rand.Int63n(9_000_000_000)+1_000_000_000)
	payload, _ := json.Marshal(map[string]any{
		"comm": map[string]any{"cv": 4747474, "ct": 24, "format": "json", "inCharset": "utf-8", "outCharset": "utf-8", "notice": 0, "platform": "yqq.json", "needNewCode": 1, "uin": 0},
		"req_1": map[string]any{
			"module": "music.vkey.GetVkey",
			"method": "UrlGetVkey",
			"param": map[string]any{
				"guid": guid, "songmid": []string{mid, mid}, "songtype": []int{0, 0},
				"uin": "0", "loginflag": 1, "platform": "20", "filename": filenames,
			},
		},
	})
	req, err := http.NewRequest(http.MethodPost, "https://u.y.qq.com/cgi-bin/musicu.fcg", bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", qq.UserAgent)
	req.Header.Set("Referer", qq.DownloadReferer)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Cookie", core.CM.Get("qq"))
	resp, err := qqHTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	var result struct {
		Req1 struct {
			Data struct {
				MidURLInfo []struct {
					Filename string `json:"filename"`
					Purl     string `json:"purl"`
				} `json:"midurlinfo"`
			} `json:"data"`
		} `json:"req_1"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return "", fmt.Errorf("qq 返回格式不对: %w", err)
	}
	for _, want := range filenames {
		for _, info := range result.Req1.Data.MidURLInfo {
			if info.Filename == want && info.Purl != "" {
				song.Ext = strings.TrimPrefix(want[strings.LastIndex(want, "."):], ".")
				return "https://ws.stream.qqmusic.qq.com/" + info.Purl, nil
			}
		}
	}
	return "", errors.New("没有拿到 QQ 音乐的播放地址，可能需要会员")
}

// bilibiliStreamURL 只取 B 站视频的音轨（DASH audio）。默认要普通音轨里码率最高的一路（一般是 192k 的 30280），
// 不要杜比全景声和 Hi-Res；要无损时先试 Hi-Res FLAC（30251，需要大会员），拿不到就退回普通音轨。
func bilibiliStreamURL(song *model.Song, lossless bool) (string, error) {
	var bvid, cid string
	if song.Extra != nil {
		bvid, cid = song.Extra["bvid"], song.Extra["cid"]
	}
	if bvid == "" || cid == "" {
		parts := strings.Split(song.ID, "|")
		if len(parts) != 2 {
			return "", fmt.Errorf("B 站歌曲 ID 格式不对: %s", song.ID)
		}
		bvid, cid = parts[0], parts[1]
	}
	// fnval=16 只要 DASH，4048 额外要杜比和 Hi-Res。
	fnval := 16
	if lossless {
		fnval = 4048
	}
	apiURL := fmt.Sprintf("https://api.bilibili.com/x/player/playurl?fnval=%d&fourk=0&bvid=%s&cid=%s", fnval, bvid, cid)
	req, err := http.NewRequest(http.MethodGet, apiURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", bilibili.UserAgent)
	req.Header.Set("Referer", bilibili.Referer)
	req.Header.Set("Cookie", core.CM.Get("bilibili"))
	resp, err := qqHTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	type audio struct {
		ID      int    `json:"id"`
		BaseURL string `json:"baseUrl"`
	}
	var result struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
		Data    struct {
			Dash struct {
				Audio []audio `json:"audio"`
				Flac  struct {
					Audio *audio `json:"audio"`
				} `json:"flac"`
			} `json:"dash"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return "", fmt.Errorf("B 站返回格式不对: %w", err)
	}
	if result.Code != 0 {
		return "", fmt.Errorf("B 站拒绝了请求: %d %s", result.Code, result.Message)
	}
	if f := result.Data.Dash.Flac.Audio; lossless && f != nil && f.BaseURL != "" {
		song.Ext = "flac"
		return f.BaseURL, nil
	}
	best := audio{ID: -1}
	for _, a := range result.Data.Dash.Audio {
		if a.BaseURL != "" && a.ID > best.ID {
			best = a
		}
	}
	if best.BaseURL == "" {
		return "", errors.New("没有拿到 B 站的音轨地址")
	}
	song.Ext = "m4a"
	return best.BaseURL, nil
}
