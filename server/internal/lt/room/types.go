// Package room 实现一起听的房间：共享歌单、投票置顶、锁定下一首、跳过、点赞、房间记录和统一开播时钟。
// 所有状态只由服务器修改，客户端以广播为准。
package room

import (
	"time"
)

const (
	MaxMembers     = 10
	QueueMax       = 100
	HistoryMax     = 50
	LockBefore     = 15 * time.Second
	PrepareTimeout = 3 * time.Second
	StartLead      = 800 * time.Millisecond
	OfflineGrace   = 60 * time.Second
	EmptyTTL       = 10 * time.Minute
	// SkipNeed 是跳过当前歌需要的人数；在线人数更少时，所有在线的人都同意才跳过。
	SkipNeed    = 2
	ActivityMax = 100
	// 点赞不限总数。App 把连点攒成一批发（like 带 count），一批最多算 LikeBatchMax 个，
	// 两批之间至少隔 LikeMinGap，挡住脚本刷包。
	LikeBatchMax = 50
	LikeMinGap   = 150 * time.Millisecond
)

// 房间记录的种类。
const (
	ActivityAdd  = "add"
	ActivityLike = "like"
	ActivitySkip = "skip"
)

const (
	StatusIdle      = "idle"
	StatusPreparing = "preparing"
	StatusPlaying   = "playing"
	// StatusPaused 只出现在放映厅：停在 PlaybackState.PositionMs。
	StatusPaused = "paused"
)

// 房间类型：听歌房间是「电台」，时间线一直往前走；放映厅可以暂停、拖进度。
const (
	KindMusic = "music"
	KindVideo = "video"
)

// 放映厅的进度控制档位。管理员在任何档位下都能直接控制。
const (
	PolicyFree      = "free"      // 所有人
	PolicyRequester = "requester" // 当前视频的点播人
	PolicyAdmins    = "admins"    // 只有管理员
)

func ValidPolicy(p string) bool {
	return p == PolicyFree || p == PolicyRequester || p == PolicyAdmins
}

// PolicyName 是档位的中文名，系统消息里用。
func PolicyName(p string) string {
	switch p {
	case PolicyFree:
		return "自由"
	case PolicyRequester:
		return "点播人做主"
	case PolicyAdmins:
		return "管理员做主"
	}
	return p
}

// 放映厅的控制动作。
const (
	ActionPause = "pause"
	ActionPlay  = "play"
	ActionSeek  = "seek"
	ActionNext  = "next"
)

const (
	// VideoPrepareTimeout：视频首帧比音频慢，多等一会儿。
	VideoPrepareTimeout = 5 * time.Second
	// RequestTTL 是没权限的人发出的控制请求的有效期。
	RequestTTL = 10 * time.Second
	// SeekEndGuard：拖到离结尾这么近时按这个位置算，免得一拖就直接放完。
	SeekEndGuard  = time.Second
	ChatMax       = 50
	DanmakuMaxLen = 40
	DanmakuMinGap = time.Second
	// MuteForever 是「一直禁言，直到解除」。存成 2100 年到期，免得多一个字段。
	MuteForever = -1 * time.Second
	ChatDanmaku = "danmaku"
	ChatSystem  = "system"
	// ChatVideo 是换到新视频时的分隔：Text 是标题，UserID/Nickname 是点播人。
	ChatVideo      = "video"
	ResultAccepted = "accepted"
	ResultRejected = "rejected"
	ResultExpired  = "expired"
)

const (
	TrackReady  = "ready"
	TrackFailed = "failed"
)

// 错误码与规格文档的“错误码”表一致。
const (
	CodeBadRequest   = "BAD_REQUEST"
	CodeNotInRoom    = "NOT_IN_ROOM"
	CodeRoomFull     = "ROOM_FULL"
	CodeForbidden    = "FORBIDDEN"
	CodeUnplayable   = "UNPLAYABLE"
	CodeQueueFull    = "QUEUE_FULL"
	CodeItemLocked   = "ITEM_LOCKED"
	CodeNotFound     = "NOT_FOUND"
	CodeRoomNotFound = "ROOM_NOT_FOUND"
	CodeRateLimited  = "RATE_LIMITED"
)

type Error struct {
	Code   string
	Msg    string
	ItemID int
}

func (e *Error) Error() string {
	if e.Msg != "" {
		return e.Code + ": " + e.Msg
	}
	return e.Code
}

func errCode(code, msg string) *Error { return &Error{Code: code, Msg: msg} }

type TrackRef struct {
	Source     string   `json:"source"`
	ID         string   `json:"id"`
	Title      string   `json:"title"`
	Artists    []string `json:"artists"`
	Album      string   `json:"album,omitempty"`
	DurationMs int64    `json:"durationMs"`
	CoverURL   string   `json:"coverUrl,omitempty"`
}

func (t TrackRef) Key() string { return t.Source + ":" + t.ID }

type QueueItem struct {
	ItemID      int      `json:"itemId"`
	Track       TrackRef `json:"track"`
	RequestedBy string   `json:"requestedBy"`
	AddedAt     int64    `json:"addedAt"`
	VoterIDs    []string `json:"voterIds"`
}

type PlaybackState struct {
	Status     string     `json:"status"`
	Item       *QueueItem `json:"item"`
	StartAt    *int64     `json:"startAt"`
	SkipperIDs []string   `json:"skipperIds"`
	// Likes 是当前歌收到的赞（同一人可以连点），LikerIDs 是点过赞的人。
	Likes    int      `json:"likes"`
	LikerIDs []string `json:"likerIds"`
	Seq      int      `json:"seq"`
	// PositionMs 只在放映厅暂停时有：停在哪里。
	PositionMs *int64 `json:"positionMs,omitempty"`
}

// VideoState 是放映厅的控制状态。客户端据此判断自己能不能直接控制：
// Effective 是 free；或自己是管理员；或 Effective 是 requester 且自己点了当前视频。
type VideoState struct {
	// Policy 是这个房间的档位；服主锁定时就是服主的默认档位。
	Policy string `json:"policy"`
	Locked bool   `json:"locked"`
	// Effective 是有权限的人掉线兜底后实际生效的档位。
	Effective string `json:"effective"`
	// AdminIDs 是房间里的管理员（含离线）。
	AdminIDs []string         `json:"adminIds"`
	Requests []ControlRequest `json:"requests"`
	// Muted 是房间成员里被禁言的人 -> 到期时间（Unix 毫秒）。
	Muted map[string]int64 `json:"muted"`
}

// ControlRequest 是没权限的人想做的操作，等有权限的人同意。
type ControlRequest struct {
	ID         int    `json:"id"`
	UserID     string `json:"userId"`
	Nickname   string `json:"nickname"`
	Action     string `json:"action"`
	PositionMs int64  `json:"positionMs,omitempty"`
	ExpiresAt  int64  `json:"expiresAt"`
}

// ChatMessage 是放映厅消息流里的一条：朋友弹幕、系统提示，或换视频时的分隔。At 是服务器时间。
type ChatMessage struct {
	ID       int    `json:"id"`
	At       int64  `json:"at"`
	Kind     string `json:"kind"`
	UserID   string `json:"userId,omitempty"`
	Nickname string `json:"nickname,omitempty"`
	Text     string `json:"text"`
}

// Activity 是房间记录里的一条：谁点了什么歌、谁点赞了、哪首歌被跳过。
// 昵称按发生时记下，成员改名或离开后记录不变。
type Activity struct {
	ID       int      `json:"id"`
	At       int64    `json:"at"`
	Kind     string   `json:"kind"`
	UserID   string   `json:"userId,omitempty"`
	Nickname string   `json:"nickname,omitempty"`
	Track    TrackRef `json:"track"`
	// 跳过时是投跳过票的人。
	Names []string `json:"names,omitempty"`
}

type MemberView struct {
	UserID   string `json:"userId"`
	Nickname string `json:"nickname"`
	Avatar   string `json:"avatar"`
	Online   bool   `json:"online"`
	JoinedAt int64  `json:"joinedAt"`
}

type Snapshot struct {
	RoomCode string        `json:"roomCode"`
	Kind     string        `json:"kind"`
	OwnerID  string        `json:"ownerId"`
	Members  []MemberView  `json:"members"`
	Playback PlaybackState `json:"playback"`
	Next     *QueueItem    `json:"next"`
	Queue    []QueueItem   `json:"queue"`
	History  []QueueItem   `json:"history"`
	// Activity 是房间记录，新的在前。
	Activity []Activity `json:"activity"`
	Rev      int        `json:"rev"`
	// 以下只有放映厅有。
	Video *VideoState   `json:"video,omitempty"`
	Chat  []ChatMessage `json:"chat,omitempty"`
}

type Summary struct {
	RoomCode    string    `json:"roomCode"`
	Kind        string    `json:"kind"`
	OwnerName   string    `json:"ownerNickname"`
	MemberCount int       `json:"memberCount"`
	OnlineCount int       `json:"onlineCount"`
	NowPlaying  *TrackRef `json:"nowPlaying"`
}

// Event 是广播给房间内所有连接的消息，Type 即协议里的 t 字段。
type Event struct {
	Type string
	Data any
}

// VideoEnv 是放映厅要用的服务器级设置，由接口层提供。
type VideoEnv struct {
	IsAdmin func(userID string) bool
	// Defaults 返回服主设的默认档位和是否锁定。
	Defaults func() (policy string, locked bool)
	// MutedUntil 返回禁言到期时间，零值表示没被禁言。
	MutedUntil func(userID string) time.Time
	// SetMute 保存禁言（零值表示解除），之后由接口层通知所有放映厅。
	SetMute func(userID string, until time.Time) error
}

func (e VideoEnv) mutedUntil(id string, now time.Time) time.Time {
	if e.MutedUntil == nil {
		return time.Time{}
	}
	if t := e.MutedUntil(id); t.After(now) {
		return t
	}
	return time.Time{}
}

func (e VideoEnv) isAdmin(id string) bool { return e.IsAdmin != nil && e.IsAdmin(id) }

func (e VideoEnv) defaults() (string, bool) {
	if e.Defaults == nil {
		return PolicyRequester, false
	}
	p, locked := e.Defaults()
	if !ValidPolicy(p) {
		p = PolicyRequester
	}
	return p, locked
}

type Broadcaster interface {
	Broadcast(roomCode string, ev Event)
}

// Clock 便于测试时用假时钟驱动计时器。
type Clock interface {
	Now() time.Time
	AfterFunc(d time.Duration, f func()) Timer
}

type Timer interface {
	Stop() bool
}

type realClock struct{}

func (realClock) Now() time.Time { return time.Now() }

func (realClock) AfterFunc(d time.Duration, f func()) Timer { return time.AfterFunc(d, f) }

func RealClock() Clock { return realClock{} }
