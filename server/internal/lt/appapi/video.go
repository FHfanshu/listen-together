package appapi

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/go-music-dl/internal/lt/room"
	"github.com/guohuiyuan/go-music-dl/internal/lt/videourl"
)

// 放映厅：服主在管理页设管理员、默认档位和锁定；App 用 /video 拿视频轨和音轨，直连失败走 /vstream。

const (
	settingVideoPolicy = "video_policy"
	settingVideoLocked = "video_policy_locked"
)

var errNeedAdmin = errors.New("还没有管理员，先在成员列表里设置管理员")

// videoEnv 是管理员名单和默认档位的内存副本，房间判断权限时不用每次查库。
type videoEnv struct {
	mu     sync.RWMutex
	admins map[string]bool
	mutes  map[string]time.Time
	policy string
	locked bool
}

func (a *API) loadVideoEnv() {
	env := &videoEnv{admins: map[string]bool{}, mutes: map[string]time.Time{}, policy: room.PolicyRequester}
	if m, err := a.Members.Mutes(); err == nil {
		env.mutes = m
	}
	if ids, err := a.Members.AdminIDs(); err == nil {
		for _, id := range ids {
			env.admins[id] = true
		}
	}
	if p := a.Members.Setting(settingVideoPolicy, room.PolicyRequester); room.ValidPolicy(p) {
		env.policy = p
	}
	env.locked = a.Members.Setting(settingVideoLocked, "") == "1"
	a.video = env
	a.Rooms.Video = room.VideoEnv{
		IsAdmin: func(id string) bool {
			env.mu.RLock()
			defer env.mu.RUnlock()
			return env.admins[id]
		},
		Defaults: func() (string, bool) {
			env.mu.RLock()
			defer env.mu.RUnlock()
			return env.policy, env.locked
		},
		MutedUntil: func(id string) time.Time {
			env.mu.RLock()
			defer env.mu.RUnlock()
			return env.mutes[id]
		},
		SetMute: a.setMute,
	}
}

func (e *videoEnv) settings() gin.H {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return gin.H{"policy": e.policy, "locked": e.locked, "hasAdmins": len(e.admins) > 0}
}

// setAdmin 改管理员。最后一个管理员被取消、而默认档位是「管理员做主」时，默认档位自动改回「点播人做主」。
func (a *API) setAdmin(id string, admin bool) (reverted bool, err error) {
	if err := a.Members.SetAdmin(id, admin); err != nil {
		return false, err
	}
	e := a.video
	e.mu.Lock()
	if admin {
		e.admins[id] = true
	} else {
		delete(e.admins, id)
	}
	if len(e.admins) == 0 && e.policy == room.PolicyAdmins {
		e.policy = room.PolicyRequester
		reverted = true
	}
	e.mu.Unlock()
	if reverted {
		_ = a.Members.SetSetting(settingVideoPolicy, room.PolicyRequester)
	}
	a.Rooms.VideoSettingsChanged()
	return reverted, nil
}

// setMute 保存禁言（零值解除），所有放映厅重新广播谁被禁言了。
func (a *API) setMute(id string, until time.Time) error {
	var p *time.Time
	if !until.IsZero() {
		p = &until
	}
	if err := a.Members.SetMute(id, p); err != nil {
		return err
	}
	e := a.video
	e.mu.Lock()
	if until.IsZero() {
		delete(e.mutes, id)
	} else {
		e.mutes[id] = until
	}
	e.mu.Unlock()
	a.Rooms.VideoSettingsChanged()
	return nil
}

func (a *API) setVideoDefaults(policy string, locked bool) error {
	if !room.ValidPolicy(policy) {
		return fmt.Errorf("档位只能是 free、requester 或 admins")
	}
	e := a.video
	e.mu.Lock()
	if policy == room.PolicyAdmins && len(e.admins) == 0 {
		e.mu.Unlock()
		return errNeedAdmin
	}
	e.policy, e.locked = policy, locked
	e.mu.Unlock()
	lockedStr := ""
	if locked {
		lockedStr = "1"
	}
	if err := a.Members.SetSetting(settingVideoPolicy, policy); err != nil {
		return err
	}
	if err := a.Members.SetSetting(settingVideoLocked, lockedStr); err != nil {
		return err
	}
	a.Rooms.VideoSettingsChanged()
	return nil
}

func (a *API) mountVideoAdmin(g *gin.RouterGroup) {
	g.POST("/members/:id/admin", func(c *gin.Context) {
		var req struct {
			Admin bool `json:"admin"`
		}
		_ = c.ShouldBindJSON(&req)
		reverted, err := a.setAdmin(c.Param("id"), req.Admin)
		if err != nil {
			c.JSON(400, gin.H{"error": err.Error()})
			return
		}
		c.JSON(200, gin.H{"ok": true, "policyReverted": reverted})
	})
	// 服主在管理页禁言或解除：minutes 0 解除，-1 一直禁言。
	g.POST("/members/:id/mute", func(c *gin.Context) {
		var req struct {
			Minutes int `json:"minutes"`
		}
		_ = c.ShouldBindJSON(&req)
		var until time.Time
		switch {
		case req.Minutes < 0:
			until = time.Date(2100, 1, 1, 0, 0, 0, 0, time.UTC)
		case req.Minutes > 0:
			until = time.Now().Add(time.Duration(req.Minutes) * time.Minute)
		}
		if err := a.setMute(c.Param("id"), until); err != nil {
			c.JSON(400, gin.H{"error": err.Error()})
			return
		}
		c.JSON(200, gin.H{"ok": true})
	})
	g.GET("/video_settings", func(c *gin.Context) { c.JSON(200, a.video.settings()) })
	g.PUT("/video_settings", func(c *gin.Context) {
		var req struct {
			Policy string `json:"policy"`
			Locked bool   `json:"locked"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(400, gin.H{"error": err.Error()})
			return
		}
		if err := a.setVideoDefaults(req.Policy, req.Locked); err != nil {
			c.JSON(400, gin.H{"error": err.Error()})
			return
		}
		c.JSON(200, a.video.settings())
	})
}

func (a *API) mountVideo(auth *gin.RouterGroup) {
	auth.GET("/video/:id", a.handleVideo)
	auth.GET("/vstream/:id/:track", a.handleVideoStream)
}

func qnParam(c *gin.Context, def int) int {
	if q, err := strconv.Atoi(c.Query("qn")); err == nil && q > 0 {
		return q
	}
	return def
}

// handleVideo 返回视频轨和音轨的直连地址，以及走代理时用的地址（代理画质不超过 720P）。
func (a *API) handleVideo(c *gin.Context) {
	id := c.Param("id")
	if c.Query("source") == YouTubeSource {
		c.JSON(400, gin.H{"error": room.CodeBadRequest, "msg": "YouTube 视频由 App 自己解析"})
		return
	}
	res, err := a.Videos.Lookup(id, qnParam(c, videourl.DefaultQn), c.Query("refresh") == "1")
	if err != nil {
		code := 502
		if errors.Is(err, videourl.ErrBadID) {
			code = 400
		}
		c.JSON(code, gin.H{"error": room.CodeUnplayable, "msg": err.Error()})
		return
	}
	proxyQn := min(res.Qn, videourl.ProxyQn)
	base := Prefix + "/vstream/" + url.PathEscape(id)
	res.ProxyVideo = fmt.Sprintf("%s/video?qn=%d", base, proxyQn)
	res.ProxyAudio = fmt.Sprintf("%s/audio?qn=%d", base, proxyQn)
	c.JSON(200, res)
}

// handleVideoStream 是直连失败后的回退：服务器拉 CDN，按 Range 转发给手机。
func (a *API) handleVideoStream(c *gin.Context) {
	track := c.Param("track")
	if track != "video" && track != "audio" {
		c.String(404, "")
		return
	}
	res, err := a.Videos.Lookup(c.Param("id"), qnParam(c, videourl.ProxyQn), false)
	if err != nil {
		c.String(502, "resolve failed")
		return
	}
	target := res.Video.URL
	if track == "audio" {
		target = res.Audio.URL
	}
	req, err := core.BuildSourceRequest(http.MethodGet, target, "bilibili", c.GetHeader("Range"))
	if err != nil {
		c.String(502, "upstream request error")
		return
	}
	resp, err := http.DefaultClient.Do(req.WithContext(c.Request.Context()))
	if err != nil {
		c.String(502, "upstream stream error")
		return
	}
	defer resp.Body.Close()
	for _, h := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if v := resp.Header.Get(h); v != "" {
			c.Header(h, v)
		}
	}
	c.Status(resp.StatusCode)
	_, _ = io.Copy(c.Writer, resp.Body)
}

// addVideo 是放映厅点视频：B 站先确认视频轨解析得出来；YouTube 由手机自己解析，只要搜到过、有时长就收。
func (a *API) addVideo(userID string, r *room.Room, source, id string) (int, error) {
	if source == YouTubeSource {
		song, ok := a.songs.get(source, id)
		if !ok || song.Name == "" {
			return 0, &room.Error{Code: room.CodeNotFound, Msg: "请先搜索或粘贴链接再点"}
		}
		if song.Duration <= 0 {
			return 0, &room.Error{Code: room.CodeUnplayable, Msg: errYouTubeLive.Error()}
		}
		return r.AddSong(userID, toTrack(song))
	}
	if source != "bilibili" {
		return 0, &room.Error{Code: room.CodeBadRequest, Msg: "放映厅只能点 B 站和 YouTube 视频"}
	}
	song := a.song(source, id)
	if song.Name == "" {
		return 0, &room.Error{Code: room.CodeNotFound, Msg: "请先粘贴链接解析再点"}
	}
	if _, err := a.Videos.Lookup(id, videourl.DefaultQn, false); err != nil {
		return 0, &room.Error{Code: room.CodeUnplayable, Msg: err.Error()}
	}
	return r.AddSong(userID, toTrack(song))
}
