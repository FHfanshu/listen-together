package appapi

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/guohuiyuan/go-music-dl/internal/lt/room"
	"github.com/guohuiyuan/music-lib/model"
	"github.com/guohuiyuan/music-lib/netease"
	"github.com/guohuiyuan/music-lib/qq"
)

var playlistURL = regexp.MustCompile(`(?i)https?://[^\s<>"']+`)
var playlistNumber = regexp.MustCompile(`^[0-9]{1,20}$`)

type playlistRef struct{ Source, ID, URL string }

type playlistPreview struct {
	Source   string          `json:"source"`
	ID       string          `json:"id"`
	URL      string          `json:"url"`
	Name     string          `json:"name"`
	Creator  string          `json:"creator"`
	CoverURL string          `json:"coverUrl"`
	Total    int             `json:"total"`
	Complete bool            `json:"complete"`
	Tracks   []room.TrackRef `json:"tracks"`
}

// Public imports deliberately use no administrator or user platform cookie.
func publicPlaylist(source, link string) (*model.Playlist, []model.Song, error) {
	if source == "netease" {
		return netease.New("").ParsePlaylist(link)
	}
	return qq.New("").ParsePlaylist(link)
}

func playlistHost(u *url.URL) bool {
	if u.User != nil || (u.Scheme != "https" && u.Scheme != "http") || (u.Port() != "" && u.Port() != "443" && u.Port() != "80") {
		return false
	}
	h := strings.ToLower(u.Hostname())
	return h == "music.163.com" || h == "y.music.163.com" || h == "163cn.tv" || h == "y.qq.com" || strings.HasSuffix(h, ".y.qq.com")
}

func playlistLink(u *url.URL) (playlistRef, bool) {
	if !playlistHost(u) {
		return playlistRef{}, false
	}
	host := strings.ToLower(u.Hostname())
	path, query := u.Path, u.Query()
	if strings.HasPrefix(u.Fragment, "/playlist?") {
		fragment, _ := url.Parse(u.Fragment)
		path, query = fragment.Path, fragment.Query()
	}
	var source, id string
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if host == "music.163.com" || host == "y.music.163.com" {
		source = "netease"
		if path == "/playlist" || path == "/m/playlist" {
			id = query.Get("id")
		} else if len(parts) >= 2 && parts[0] == "playlist" {
			id = parts[1]
		}
	} else if host == "y.qq.com" || strings.HasSuffix(host, ".y.qq.com") {
		source = "qq"
		if len(parts) == 4 && parts[0] == "n" && (parts[1] == "ryqq" || parts[1] == "yqq") && parts[2] == "playlist" {
			id = strings.TrimSuffix(parts[3], ".html")
		} else if strings.HasSuffix(path, "/taoge.html") || strings.HasSuffix(path, "/playlist.html") {
			id = query.Get("id")
			if id == "" {
				id = query.Get("disstid")
			}
		}
	}
	if !playlistNumber.MatchString(id) {
		return playlistRef{}, false
	}
	n, err := strconv.ParseUint(id, 10, 64)
	if err != nil || n == 0 {
		return playlistRef{}, false
	}
	id = strconv.FormatUint(n, 10)
	canonical := "https://music.163.com/playlist?id=" + id
	if source == "qq" {
		canonical = "https://y.qq.com/n/ryqq/playlist/" + id
	}
	return playlistRef{source, id, canonical}, true
}

func (a *API) handlePlaylist(c *gin.Context) {
	text := c.Query("url")
	if len(text) > 8192 {
		c.JSON(400, gin.H{"error": "BAD_REQUEST", "msg": "分享内容太长，请只粘贴歌单链接"})
		return
	}
	link := strings.TrimRight(playlistURL.FindString(text), "。！，、；）】》.,;)]}")
	u, err := url.Parse(link)
	if err != nil || link == "" || !playlistHost(u) {
		c.JSON(400, gin.H{"error": "BAD_PLAYLIST_LINK", "msg": "请粘贴网易云或 QQ 音乐的公开歌单链接"})
		return
	}
	ref, ok := playlistLink(u)
	if !ok && (u.Hostname() == "163cn.tv" || strings.HasSuffix(u.Path, "/fcgi-bin/u")) {
		// Resolve only known platform short links. Every redirect stays on the platform allowlist.
		client := &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 || !playlistHost(req.URL) {
				return errors.New("invalid playlist redirect")
			}
			return nil
		}}
		req, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodGet, u.String(), nil)
		req.Header.Set("User-Agent", "Mozilla/5.0")
		resp, requestErr := client.Do(req)
		if requestErr == nil {
			ref, ok = playlistLink(resp.Request.URL)
			resp.Body.Close()
		}
	}
	if !ok {
		c.JSON(400, gin.H{"error": "BAD_PLAYLIST_LINK", "msg": "没认出歌单，请在原 App 打开公开歌单后重新复制链接"})
		return
	}
	metadata, songs, err := a.Playlists(ref.Source, ref.URL)
	if err != nil || metadata == nil {
		c.JSON(502, gin.H{"error": "PLAYLIST_UNAVAILABLE", "msg": "歌单暂时读不到，请确认已公开后重试"})
		return
	}
	preview := playlistPreview{Source: ref.Source, ID: ref.ID, URL: ref.URL, Name: metadata.Name, Creator: metadata.Creator,
		CoverURL: metadata.Cover, Total: max(metadata.TrackCount, len(songs)), Tracks: []room.TrackRef{}}
	for _, song := range songs {
		if song.IsInvalid || song.ID == "" || strings.TrimSpace(song.Name) == "" || song.Source != ref.Source {
			continue
		}
		a.songs.put(song)
		preview.Tracks = append(preview.Tracks, toTrack(&song))
	}
	if len(preview.Tracks) == 0 {
		c.JSON(422, gin.H{"error": "PLAYLIST_EMPTY_OR_PRIVATE", "msg": "没有读到歌曲：歌单可能为空、未公开，或需要原平台登录"})
		return
	}
	if preview.Name == "" {
		preview.Name = fmt.Sprintf("%s 歌单", ref.Source)
	}
	preview.Complete = len(preview.Tracks) == preview.Total
	c.JSON(200, preview)
}
