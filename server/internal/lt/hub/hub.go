// Package hub 把房间的广播推送到成员的 WebSocket 连接，并把客户端消息分发给房间。
//
// 信封格式：客户端 {"t","id","d"}，服务器回 {"t":"ack","id","ok","err","d"}；广播没有 id。
package hub

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/guohuiyuan/go-music-dl/internal/lt/members"
	"github.com/guohuiyuan/go-music-dl/internal/lt/room"
)

const (
	helloTimeout = 10 * time.Second
	pingInterval = 15 * time.Second
	writeTimeout = 10 * time.Second
	sendBuffer   = 64
	// 每秒 5 条，允许短时突发 10 条。
	rateLimit = 5
	rateBurst = 10
)

type Hub struct {
	Rooms        *room.Manager
	Authenticate func(token string) (members.Member, error)
	// AddSong 由接口层提供：查歌、解析可播性，再调用 room.AddSong。
	AddSong func(userID string, r *room.Room, source, id string) (int, error)
	Now     func() time.Time

	mu    sync.Mutex
	conns map[string]*conn // userID -> 当前连接；同一账号只保留最新的一条
}

func New() *Hub {
	return &Hub{Now: time.Now, conns: map[string]*conn{}}
}

type conn struct {
	hub    *Hub
	ws     *websocket.Conn
	send   chan []byte
	user   members.Member
	cancel context.CancelFunc

	mu       sync.Mutex
	roomCode string
	closed   bool
}

type envelope struct {
	T  string          `json:"t"`
	ID string          `json:"id,omitempty"`
	D  json.RawMessage `json:"d,omitempty"`
}

type ackErr struct {
	Code   string `json:"code"`
	Msg    string `json:"msg,omitempty"`
	ItemID int    `json:"itemId,omitempty"`
}

type ack struct {
	T   string  `json:"t"`
	ID  string  `json:"id"`
	OK  bool    `json:"ok"`
	Err *ackErr `json:"err,omitempty"`
	D   any     `json:"d,omitempty"`
}

type outgoing struct {
	T string `json:"t"`
	D any    `json:"d"`
}

// Broadcast 实现 room.Broadcaster。房间持锁调用，所以这里只做非阻塞投递。
func (h *Hub) Broadcast(roomCode string, ev room.Event) {
	msg, err := json.Marshal(outgoing{T: ev.Type, D: ev.Data})
	if err != nil {
		return
	}
	h.mu.Lock()
	targets := make([]*conn, 0, len(h.conns))
	for _, c := range h.conns {
		if c.currentRoom() == roomCode {
			targets = append(targets, c)
		}
	}
	h.mu.Unlock()
	for _, c := range targets {
		c.enqueue(msg)
	}
}

// ProfileChanged 在成员改了昵称或头像后调用：更新连接上缓存的资料，并让所在房间广播。
func (h *Hub) ProfileChanged(m members.Member) {
	h.mu.Lock()
	c := h.conns[m.ID]
	h.mu.Unlock()
	if c == nil {
		return
	}
	c.mu.Lock()
	c.user.Nickname, c.user.Avatar = m.Nickname, m.Avatar
	code := c.roomCode
	c.mu.Unlock()
	if r, ok := h.Rooms.Get(code); ok {
		r.UpdateProfile(m.ID, m.Nickname, m.Avatar)
	}
}

// DisconnectUser 在管理员停用成员时把他移出房间并断开连接。
func (h *Hub) DisconnectUser(userID string) {
	h.mu.Lock()
	c := h.conns[userID]
	h.mu.Unlock()
	if c != nil {
		c.leaveCurrent()
		c.close(websocket.StatusPolicyViolation, "disabled")
	}
}

func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// 客户端是手机 App，不是浏览器页面，不做 Origin 校验；鉴权靠 hello 里的 token。
		InsecureSkipVerify: true,
	})
	if err != nil {
		return
	}
	ws.SetReadLimit(64 << 10)
	ctx, cancel := context.WithCancel(context.Background())
	c := &conn{hub: h, ws: ws, send: make(chan []byte, sendBuffer), cancel: cancel}
	defer cancel()

	if !c.handshake(ctx) {
		return
	}
	go c.writeLoop(ctx)
	c.readLoop(ctx)
	c.cleanup()
}

type helloReq struct {
	Token    string `json:"token"`
	RoomCode string `json:"roomCode"`
	LastRev  int    `json:"lastRev"`
}

func (c *conn) handshake(ctx context.Context) bool {
	hctx, cancel := context.WithTimeout(ctx, helloTimeout)
	defer cancel()
	_, data, err := c.ws.Read(hctx)
	if err != nil {
		_ = c.ws.Close(websocket.StatusPolicyViolation, "hello timeout")
		return false
	}
	var env envelope
	var req helloReq
	if json.Unmarshal(data, &env) != nil || env.T != "hello" || json.Unmarshal(env.D, &req) != nil {
		_ = c.ws.Close(websocket.StatusPolicyViolation, "expect hello")
		return false
	}
	user, err := c.hub.Authenticate(req.Token)
	if err != nil {
		c.writeNow(ctx, ack{T: "ack", ID: env.ID, Err: &ackErr{Code: "UNAUTHORIZED"}})
		_ = c.ws.Close(websocket.StatusPolicyViolation, "unauthorized")
		return false
	}
	c.user = user

	c.hub.mu.Lock()
	old := c.hub.conns[user.ID]
	c.hub.conns[user.ID] = c
	c.hub.mu.Unlock()
	if old != nil {
		old.close(websocket.StatusPolicyViolation, "replaced")
	}

	d := map[string]any{"userId": user.ID, "nickname": user.Nickname, "avatar": user.Avatar, "serverNowMs": c.hub.nowMs()}
	var snap any
	if req.RoomCode != "" {
		if s, err := c.enterRoom(req.RoomCode); err != nil {
			d["roomError"] = toAckErr(err)
		} else {
			d["roomCode"] = req.RoomCode
			snap = s
		}
	}
	c.writeNow(ctx, ack{T: "ack", ID: env.ID, OK: true, D: d})
	if snap != nil {
		c.enqueueJSON(outgoing{T: "snapshot", D: snap})
	}
	return true
}

func (c *conn) writeNow(ctx context.Context, v any) {
	msg, _ := json.Marshal(v)
	wctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	_ = c.ws.Write(wctx, websocket.MessageText, msg)
}

func (c *conn) writeLoop(ctx context.Context) {
	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-c.send:
			wctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.ws.Write(wctx, websocket.MessageText, msg)
			cancel()
			if err != nil {
				c.close(websocket.StatusGoingAway, "write failed")
				return
			}
		case <-ticker.C:
			pctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.ws.Ping(pctx)
			cancel()
			if err != nil {
				c.close(websocket.StatusGoingAway, "ping timeout")
				return
			}
		}
	}
}

func (c *conn) readLoop(ctx context.Context) {
	tokens := float64(rateBurst)
	last := time.Now()
	for {
		_, data, err := c.ws.Read(ctx)
		if err != nil {
			return
		}
		received := c.hub.nowMs()

		now := time.Now()
		tokens += now.Sub(last).Seconds() * rateLimit
		if tokens > rateBurst {
			tokens = rateBurst
		}
		last = now
		var env envelope
		if json.Unmarshal(data, &env) != nil {
			c.enqueueJSON(ack{T: "ack", Err: &ackErr{Code: room.CodeBadRequest, Msg: "bad json"}})
			continue
		}
		if tokens < 1 {
			c.enqueueJSON(ack{T: "ack", ID: env.ID, Err: &ackErr{Code: "RATE_LIMITED"}})
			continue
		}
		tokens--

		d, err := c.dispatch(env, received)
		if env.ID == "" {
			continue
		}
		if err != nil {
			c.enqueueJSON(ack{T: "ack", ID: env.ID, Err: toAckErr(err)})
		} else {
			c.enqueueJSON(ack{T: "ack", ID: env.ID, OK: true, D: d})
		}
	}
}

func (c *conn) dispatch(env envelope, receivedMs int64) (any, error) {
	switch env.T {
	case "time_sync":
		var req struct {
			T0 int64 `json:"t0"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return map[string]int64{"t0": req.T0, "t1": receivedMs, "t2": c.hub.nowMs()}, nil

	case "create_room":
		var req struct {
			Kind string `json:"kind"` // 旧版 App 不带，按听歌房间
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		c.leaveCurrent()
		r, err := c.hub.Rooms.CreateKind(req.Kind)
		if err != nil {
			return nil, err
		}
		snap, err := c.enterRoom(r.Code())
		if err != nil {
			return nil, err
		}
		c.enqueueJSON(outgoing{T: "snapshot", D: snap})
		return map[string]string{"roomCode": r.Code()}, nil

	case "join_room":
		var req struct {
			RoomCode string `json:"roomCode"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		if req.RoomCode != c.currentRoom() {
			c.leaveCurrent()
		}
		snap, err := c.enterRoom(req.RoomCode)
		if err != nil {
			return nil, err
		}
		c.enqueueJSON(outgoing{T: "snapshot", D: snap})
		return map[string]string{"roomCode": req.RoomCode}, nil

	case "leave_room":
		c.leaveCurrent()
		return nil, nil
	}

	r, err := c.room()
	if err != nil {
		return nil, err
	}
	uid := c.user.ID
	switch env.T {
	case "add_song":
		var req struct {
			Source string `json:"source"`
			ID     string `json:"id"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		if req.Source == "" || req.ID == "" {
			return nil, &room.Error{Code: room.CodeBadRequest, Msg: "source/id required"}
		}
		itemID, err := c.hub.AddSong(uid, r, req.Source, req.ID)
		if err != nil {
			return nil, err
		}
		return map[string]int{"itemId": itemID}, nil

	case "remove_song":
		var req struct {
			ItemID int `json:"itemId"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, r.RemoveSong(uid, req.ItemID)

	case "vote":
		var req struct {
			ItemID int  `json:"itemId"`
			On     bool `json:"on"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, r.Vote(uid, req.ItemID, req.On)

	case "skip", "dislike": // dislike 是旧版 App 的名字，升级前的手机仍能用
		var req struct {
			Seq int  `json:"seq"`
			On  bool `json:"on"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, r.Skip(uid, req.Seq, req.On)

	case "like":
		var req struct {
			Seq   int `json:"seq"`
			Count int `json:"count"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		likes, err := r.Like(uid, req.Seq, req.Count)
		if err != nil {
			return nil, err
		}
		return map[string]int{"likes": likes}, nil

	case "track_status":
		var req struct {
			Seq    int    `json:"seq"`
			Status string `json:"status"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, r.TrackStatus(uid, req.Seq, req.Status)

	case "control":
		var req struct {
			Action     string `json:"action"`
			Seq        int    `json:"seq"`
			PositionMs int64  `json:"positionMs"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		requested, err := r.Control(uid, req.Action, req.Seq, req.PositionMs)
		if err != nil {
			return nil, err
		}
		return map[string]bool{"requested": requested}, nil

	case "control_reply":
		var req struct {
			RequestID int  `json:"requestId"`
			Accept    bool `json:"accept"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, r.Reply(uid, req.RequestID, req.Accept)

	case "set_policy":
		var req struct {
			Policy string `json:"policy"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, r.SetPolicy(uid, req.Policy)

	case "danmaku":
		var req struct {
			Text  string `json:"text"`
			Nonce string `json:"nonce"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		if len(req.Nonce) > 64 {
			req.Nonce = req.Nonce[:64]
		}
		return nil, r.Danmaku(uid, req.Text, req.Nonce)

	case "mute":
		var req struct {
			UserID  string `json:"userId"`
			Minutes int    `json:"minutes"` // 0 解除，-1 一直禁言
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		d := time.Duration(req.Minutes) * time.Minute
		if req.Minutes < 0 {
			d = room.MuteForever
		}
		return nil, r.Mute(uid, req.UserID, d)

	case "kick":
		var req struct {
			UserID string `json:"userId"`
		}
		if err := decode(env.D, &req); err != nil {
			return nil, err
		}
		return nil, c.kick(r, req.UserID)
	}
	return nil, &room.Error{Code: room.CodeBadRequest, Msg: "unknown type " + env.T}
}

func (c *conn) kick(r *room.Room, target string) error {
	snap, _ := r.Snapshot()
	if snap.OwnerID != c.user.ID || target == c.user.ID {
		return &room.Error{Code: room.CodeForbidden}
	}
	if !r.IsMember(target) {
		return &room.Error{Code: room.CodeNotFound}
	}
	r.Leave(target)
	c.hub.mu.Lock()
	tc := c.hub.conns[target]
	c.hub.mu.Unlock()
	if tc != nil && tc.currentRoom() == r.Code() {
		tc.setRoom("")
		tc.enqueueJSON(outgoing{T: "kicked", D: map[string]string{"roomCode": r.Code()}})
	}
	return nil
}

type snapshotMsg struct {
	room.Snapshot
	ServerNowMs int64 `json:"serverNowMs"`
}

func (c *conn) enterRoom(code string) (any, error) {
	r, ok := c.hub.Rooms.Get(code)
	if !ok {
		return nil, &room.Error{Code: room.CodeRoomNotFound}
	}
	// 先登记房间再 Attach，这样 Attach 触发的 members 广播也能发到自己。
	c.setRoom(code)
	nickname, avatar := c.profile()
	if err := r.Attach(c.user.ID, nickname, avatar); err != nil {
		c.setRoom("")
		return nil, err
	}
	snap, now := r.Snapshot()
	return snapshotMsg{Snapshot: snap, ServerNowMs: now}, nil
}

func (c *conn) leaveCurrent() {
	code := c.currentRoom()
	if code == "" {
		return
	}
	c.setRoom("")
	if r, ok := c.hub.Rooms.Get(code); ok {
		r.Leave(c.user.ID)
	}
}

func (c *conn) room() (*room.Room, error) {
	code := c.currentRoom()
	if code == "" {
		return nil, &room.Error{Code: room.CodeNotInRoom}
	}
	r, ok := c.hub.Rooms.Get(code)
	if !ok || !r.IsMember(c.user.ID) {
		c.setRoom("")
		return nil, &room.Error{Code: room.CodeNotInRoom}
	}
	return r, nil
}

// cleanup 在连接断开后执行：只有自己仍是该账号的当前连接时才标记离线，
// 被新连接顶掉的旧连接不能把刚上线的人又标成离线。
func (c *conn) cleanup() {
	c.close(websocket.StatusNormalClosure, "")
	c.hub.mu.Lock()
	current := c.hub.conns[c.user.ID] == c
	if current {
		delete(c.hub.conns, c.user.ID)
	}
	c.hub.mu.Unlock()
	if !current {
		return
	}
	if code := c.currentRoom(); code != "" {
		if r, ok := c.hub.Rooms.Get(code); ok {
			r.Detach(c.user.ID)
		}
	}
}

func (c *conn) profile() (nickname, avatar string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.user.Nickname, c.user.Avatar
}

func (c *conn) currentRoom() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.roomCode
}

func (c *conn) setRoom(code string) {
	c.mu.Lock()
	c.roomCode = code
	c.mu.Unlock()
}

func (c *conn) enqueueJSON(v any) {
	msg, err := json.Marshal(v)
	if err == nil {
		c.enqueue(msg)
	}
}

// enqueue 发送缓冲满了说明客户端跟不上，直接断开让它重连拿快照。
func (c *conn) enqueue(msg []byte) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	select {
	case c.send <- msg:
		c.mu.Unlock()
	default:
		c.mu.Unlock()
		c.close(websocket.StatusPolicyViolation, "slow consumer")
	}
}

func (c *conn) close(code websocket.StatusCode, reason string) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	c.mu.Unlock()
	go func() {
		_ = c.ws.Close(code, reason)
		c.cancel()
	}()
}

func (h *Hub) nowMs() int64 { return h.Now().UnixMilli() }

func decode(raw json.RawMessage, v any) error {
	if len(raw) == 0 {
		raw = []byte("{}")
	}
	if err := json.Unmarshal(raw, v); err != nil {
		return &room.Error{Code: room.CodeBadRequest, Msg: err.Error()}
	}
	return nil
}

func toAckErr(err error) *ackErr {
	var re *room.Error
	if errors.As(err, &re) {
		return &ackErr{Code: re.Code, Msg: re.Msg, ItemID: re.ItemID}
	}
	return &ackErr{Code: "INTERNAL", Msg: err.Error()}
}
