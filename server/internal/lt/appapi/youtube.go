package appapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/guohuiyuan/music-lib/model"
)

// YouTube 只进放映厅。服务器只负责搜索和拿标题、时长（走 YouTube 的 InnerTube 接口，服务器经代理出网）；
// 视频地址绑定解析它的那台机器的 IP，所以由每台手机用自己的网络解析、直连播放，服务器不转发。

const YouTubeSource = "youtube"

var (
	errYouTubeNotVideo = errors.New("没认出 YouTube 视频，粘贴视频链接或输入关键词")
	errYouTubeLive     = errors.New("直播和首映还没开始的视频不能点")
	// youtubeLinkPattern 认 watch?v=、youtu.be/、shorts/、live/、embed/，包括 m. 和 music. 子域名。
	youtubeLinkPattern = regexp.MustCompile(`(?i)(?:youtube\.com/(?:watch\?(?:[^\s#]*&)?v=|shorts/|live/|embed/|v/)|youtu\.be/)([0-9A-Za-z_-]{11})`)
	youtubeSearchMax   = 20
	youtubeHTTP        = &http.Client{Timeout: 12 * time.Second}
)

// youtubeClient 是 InnerTube 请求，测试里替换。
type youtubeClient struct {
	post func(endpoint string, body any) ([]byte, error)
}

func newYouTubeClient() *youtubeClient { return &youtubeClient{post: innertubePost} }

func looksLikeYouTube(text string) bool { return youtubeLinkPattern.MatchString(text) }

// parseYouTubeID 从链接里找视频 ID，没有返回空。
func parseYouTubeID(text string) string {
	text = strings.TrimSpace(text)
	if m := youtubeLinkPattern.FindStringSubmatch(text); m != nil {
		return m[1]
	}
	return ""
}

// lookup 粘贴链接时返回这一个视频；否则按关键词搜。
func (y *youtubeClient) lookup(text string) ([]model.Song, error) {
	text = strings.TrimSpace(text)
	if id := parseYouTubeID(text); id != "" {
		s, err := y.video(id)
		if err != nil {
			return nil, err
		}
		return []model.Song{s}, nil
	}
	if strings.Contains(text, "://") {
		return nil, errYouTubeNotVideo
	}
	return y.search(text)
}

const innertubeVersion = "2.20250101.00.00"

func innertubeContext() map[string]any {
	return map[string]any{"client": map[string]any{"clientName": "WEB", "clientVersion": innertubeVersion, "hl": "zh-CN"}}
}

func innertubePost(endpoint string, body any) ([]byte, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest(http.MethodPost, "https://www.youtube.com/youtubei/v1/"+endpoint+"?prettyPrint=false", bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := youtubeHTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("连不上 YouTube（服务器要能访问外网）: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("YouTube HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 8<<20))
}

// video 拿一个视频的标题、作者和时长。播放状态不看（服务器这边常常是 UNPLAYABLE，手机那边能放），
// 只要求有时长、不是直播。
func (y *youtubeClient) video(id string) (model.Song, error) {
	body, err := y.post("player", map[string]any{"context": innertubeContext(), "videoId": id})
	if err != nil {
		return model.Song{}, err
	}
	var resp struct {
		VideoDetails *struct {
			VideoID       string `json:"videoId"`
			Title         string `json:"title"`
			Author        string `json:"author"`
			LengthSeconds string `json:"lengthSeconds"`
			IsLive        bool   `json:"isLive"`
			IsUpcoming    bool   `json:"isUpcoming"`
		} `json:"videoDetails"`
		PlayabilityStatus struct {
			Status string `json:"status"`
			Reason string `json:"reason"`
		} `json:"playabilityStatus"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return model.Song{}, fmt.Errorf("YouTube 返回格式不对: %w", err)
	}
	d := resp.VideoDetails
	if d == nil || d.VideoID == "" {
		msg := resp.PlayabilityStatus.Reason
		if msg == "" {
			msg = "视频不存在或不可用"
		}
		return model.Song{}, errors.New("YouTube：" + msg)
	}
	secs, _ := strconv.Atoi(d.LengthSeconds)
	if d.IsLive || d.IsUpcoming || secs <= 0 {
		return model.Song{}, errYouTubeLive
	}
	return youtubeSong(d.VideoID, d.Title, d.Author, secs), nil
}

func youtubeSong(id, title, author string, secs int) model.Song {
	return model.Song{
		Source:   YouTubeSource,
		ID:       id,
		Name:     title,
		Artist:   author,
		Duration: secs,
		Cover:    "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg",
		Link:     "https://www.youtube.com/watch?v=" + id,
	}
}

// search 只要普通视频（params 是「类型：视频」筛选），跳过直播和没有时长的。
func (y *youtubeClient) search(q string) ([]model.Song, error) {
	body, err := y.post("search", map[string]any{"context": innertubeContext(), "query": q, "params": "EgIQAQ=="})
	if err != nil {
		return nil, err
	}
	return parseYouTubeSearch(body)
}

type ytText struct {
	SimpleText string `json:"simpleText"`
	Runs       []struct {
		Text string `json:"text"`
	} `json:"runs"`
}

func (t ytText) String() string {
	if t.SimpleText != "" {
		return t.SimpleText
	}
	var b strings.Builder
	for _, r := range t.Runs {
		b.WriteString(r.Text)
	}
	return b.String()
}

type ytVideoRenderer struct {
	VideoID    string `json:"videoId"`
	Title      ytText `json:"title"`
	LengthText ytText `json:"lengthText"`
	OwnerText  ytText `json:"ownerText"`
}

// 搜索结果在 contents.twoColumnSearchResultsRenderer.primaryContents.sectionListRenderer.contents[]
// .itemSectionRenderer.contents[].videoRenderer，按这个顺序取，保持 YouTube 的排序。
type ytSearchResp struct {
	Contents struct {
		TwoColumn struct {
			Primary struct {
				SectionList struct {
					Contents []struct {
						ItemSection struct {
							Contents []struct {
								Video *ytVideoRenderer `json:"videoRenderer"`
							} `json:"contents"`
						} `json:"itemSectionRenderer"`
					} `json:"contents"`
				} `json:"sectionListRenderer"`
			} `json:"primaryContents"`
		} `json:"twoColumnSearchResultsRenderer"`
	} `json:"contents"`
}

func parseYouTubeSearch(body []byte) ([]model.Song, error) {
	var resp ytSearchResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("YouTube 返回格式不对: %w", err)
	}
	songs := []model.Song{}
	seen := map[string]bool{}
	for _, sec := range resp.Contents.TwoColumn.Primary.SectionList.Contents {
		for _, item := range sec.ItemSection.Contents {
			vr := item.Video
			if vr == nil || vr.VideoID == "" || seen[vr.VideoID] {
				continue
			}
			secs := parseClock(vr.LengthText.String())
			if secs <= 0 {
				continue
			}
			seen[vr.VideoID] = true
			songs = append(songs, youtubeSong(vr.VideoID, vr.Title.String(), vr.OwnerText.String(), secs))
			if len(songs) >= youtubeSearchMax {
				return songs, nil
			}
		}
	}
	return songs, nil
}

// parseClock 把 "1:02:03" / "5:19" 换成秒，认不出返回 0。
func parseClock(s string) int {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	total := 0
	for _, p := range strings.Split(s, ":") {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return 0
		}
		total = total*60 + n
	}
	return total
}
