package room

// 放映厅：在听歌房间的歌单、置顶投票、跳过投票之上，加上暂停、拖进度、控制档位、控制请求和朋友弹幕。
//
// 时间线仍用 startAt 表示：播放中位置 = 服务器时间 − startAt；暂停时停在 pausePos。
// 拖进度就是把 startAt 挪到「现在 + StartLead − 新位置」，客户端的纠偏逻辑不用变。

import (
	"fmt"
	"slices"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

type videoState struct {
	// policy 是管理员给这个房间设的档位；空表示跟随服主的默认档位。
	policy   string
	pausePos time.Duration

	requests map[string]*pendingRequest // 发起人 -> 请求，每人同时只有一个
	reqSeq   int

	chat        []ChatMessage
	chatSeq     int
	lastDanmaku map[string]time.Time
	// 每人最近几条弹幕的客户端编号：回执丢了 App 重发同一条时只算一次。
	danmakuNonces map[string][]string

	// 上次广播时的状态，用来判断要不要发「兜底」提示。
	lastBase, lastEffective string
	lastItemID              int
}

type pendingRequest struct {
	req   ControlRequest
	timer Timer
}

// ---- 档位 ----

// basePolicyLocked 返回房间的档位和是否被服主锁定。
func (r *Room) basePolicyLocked() (string, bool) {
	def, locked := r.env.defaults()
	if locked || r.video.policy == "" {
		return def, locked
	}
	return r.video.policy, false
}

func (r *Room) onlineAdminLocked() bool {
	for id, m := range r.members {
		if m.online && r.env.isAdmin(id) {
			return true
		}
	}
	return false
}

// effectivePolicyLocked 是兜底后的档位：有权限的人都不在线时不能让房间卡死。
// 点播人做主：点播人不在线 → 管理员；管理员也不在 → 自由。管理员做主：管理员都不在 → 自由。
func (r *Room) effectivePolicyLocked() string {
	base, _ := r.basePolicyLocked()
	if r.current == nil || base == PolicyFree {
		return base
	}
	if base == PolicyRequester {
		if m, ok := r.members[r.current.RequestedBy]; ok && m.online {
			return PolicyRequester
		}
		if r.onlineAdminLocked() {
			return PolicyAdmins
		}
		return PolicyFree
	}
	if r.onlineAdminLocked() {
		return PolicyAdmins
	}
	return PolicyFree
}

func (r *Room) canControlLocked(userID string) bool {
	if r.env.isAdmin(userID) {
		return true
	}
	switch r.effectivePolicyLocked() {
	case PolicyFree:
		return true
	case PolicyRequester:
		return r.current != nil && r.current.RequestedBy == userID
	}
	return false
}

func (r *Room) videoViewLocked() VideoState {
	base, locked := r.basePolicyLocked()
	v := VideoState{Policy: base, Locked: locked, Effective: r.effectivePolicyLocked(), AdminIDs: []string{}, Requests: []ControlRequest{}}
	for id := range r.members {
		if r.env.isAdmin(id) {
			v.AdminIDs = append(v.AdminIDs, id)
		}
	}
	sort.Strings(v.AdminIDs)
	v.Muted = map[string]int64{}
	now := r.clock.Now()
	for id := range r.members {
		if t := r.env.mutedUntil(id, now); !t.IsZero() {
			v.Muted[id] = t.UnixMilli()
		}
	}
	for _, p := range r.video.requests {
		v.Requests = append(v.Requests, p.req)
	}
	sort.Slice(v.Requests, func(i, j int) bool { return v.Requests[i].ID < v.Requests[j].ID })
	return v
}

// refreshVideoLocked 在成员、当前视频、档位或管理员变化后调用：广播控制状态，
// 档位没变、只是有权限的人掉线或回来导致实际档位变化时，在消息流里公开说一声。
func (r *Room) refreshVideoLocked() {
	if !r.isVideo() || r.closed {
		return
	}
	base, _ := r.basePolicyLocked()
	eff := r.effectivePolicyLocked()
	itemID := 0
	if r.current != nil {
		itemID = r.current.ItemID
	}
	v := &r.video
	if r.current != nil && v.lastBase == base && v.lastEffective != "" && eff != v.lastEffective {
		switch {
		case eff == base:
			r.addSystemLocked("有权限的人回来了，恢复为「" + PolicyName(base) + "」")
		case eff == PolicyFree:
			r.addSystemLocked("有权限的人都不在线，暂时所有人都能控制进度")
		case eff == PolicyAdmins:
			r.addSystemLocked("点播人不在线，暂由管理员控制进度")
		}
	} else if r.current != nil && v.lastItemID != itemID && eff != base {
		// 换了视频，新视频的点播人已经不在线。
		switch eff {
		case PolicyFree:
			r.addSystemLocked("点播人和管理员都不在线，这个视频所有人都能控制进度")
		case PolicyAdmins:
			r.addSystemLocked("点播人不在线，这个视频由管理员控制进度")
		}
	}
	v.lastBase, v.lastEffective, v.lastItemID = base, eff, itemID
	r.emit("video", map[string]any{"video": r.videoViewLocked(), "rev": r.rev})
}

func (r *Room) videoSettingsChanged() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.refreshVideoLocked()
}

// SetPolicy 由管理员修改这个房间的档位；服主锁定时谁都不能改。
func (r *Room) SetPolicy(userID, policy string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, err := r.videoMemberLocked(userID)
	if err != nil {
		return err
	}
	if !ValidPolicy(policy) {
		return errCode(CodeBadRequest, "档位只能是 free、requester 或 admins")
	}
	if !r.env.isAdmin(userID) {
		return errCode(CodeForbidden, "只有管理员能改档位")
	}
	cur, locked := r.basePolicyLocked()
	if locked {
		return errCode(CodeForbidden, "服主锁定了档位")
	}
	if cur == policy {
		return nil
	}
	r.video.policy = policy
	r.rev++
	r.addSystemLocked(m.nickname + "（管理员）把档位改成了「" + PolicyName(policy) + "」")
	r.refreshVideoLocked()
	return nil
}

// ---- 控制 ----

// Control 是暂停、继续、拖进度、切下一个。seq 不是当前值说明客户端看到的是上一个视频，忽略。
// 没权限时不报错，而是变成一个请求等有权限的人同意，返回 requested=true。
func (r *Room) Control(userID, action string, seq int, positionMs int64) (requested bool, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, err := r.videoMemberLocked(userID)
	if err != nil {
		return false, err
	}
	switch action {
	case ActionPause, ActionPlay, ActionSeek, ActionNext:
	default:
		return false, errCode(CodeBadRequest, "未知的操作 "+action)
	}
	if seq != r.seq || r.current == nil {
		return false, nil
	}
	if !r.canControlLocked(userID) {
		r.addRequestLocked(userID, m.nickname, action, positionMs)
		return true, nil
	}
	r.applyControlLocked(m.nickname, "", action, positionMs)
	return false, nil
}

// Reply 是有权限的人处理一个控制请求。
func (r *Room) Reply(userID string, requestID int, accept bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, err := r.videoMemberLocked(userID)
	if err != nil {
		return err
	}
	if !r.canControlLocked(userID) {
		return errCode(CodeForbidden, "")
	}
	var p *pendingRequest
	for _, it := range r.video.requests {
		if it.req.ID == requestID {
			p = it
			break
		}
	}
	if p == nil {
		// 已经被别人处理或过期了。
		return nil
	}
	r.dropRequestLocked(p.req.UserID)
	result := ResultRejected
	if accept {
		result = ResultAccepted
	}
	r.emit("control_result", map[string]any{"requestId": p.req.ID, "userId": p.req.UserID, "result": result, "byNickname": m.nickname})
	if accept {
		r.applyControlLocked(m.nickname, p.req.Nickname, p.req.Action, p.req.PositionMs)
		return nil
	}
	r.refreshVideoLocked()
	return nil
}

// applyControlLocked 执行一个控制动作并在消息流里公开。from 非空表示是同意了 from 的请求。
func (r *Room) applyControlLocked(by, from, action string, positionMs int64) {
	who := by
	if from != "" {
		who = by + " 同意了 " + from + " 的请求，"
	} else {
		who += " "
	}
	dur := time.Duration(r.current.Track.DurationMs) * time.Millisecond
	switch action {
	case ActionPause:
		if r.status != StatusPlaying {
			return
		}
		pos := r.positionLocked()
		stopTimer(&r.endT)
		stopTimer(&r.lockT)
		r.status = StatusPaused
		r.video.pausePos = pos
		r.rev++
		r.emitPlayback()
		r.addSystemLocked(who + "暂停了")
	case ActionPlay:
		if r.status != StatusPaused {
			return
		}
		r.playFromLocked(r.video.pausePos)
		r.addSystemLocked(who + "继续播放")
	case ActionSeek:
		if r.status != StatusPlaying && r.status != StatusPaused {
			return
		}
		pos := time.Duration(positionMs) * time.Millisecond
		pos = max(0, min(pos, dur-SeekEndGuard))
		if r.status == StatusPlaying {
			r.playFromLocked(pos)
		} else {
			r.video.pausePos = pos
			r.rev++
			r.emitPlayback()
		}
		r.addSystemLocked(who + "跳到了 " + formatPos(pos))
	case ActionNext:
		r.addSystemLocked(who + "切到了下一个")
		r.addActivityLocked(Activity{Kind: ActivitySkip, Track: r.current.Track, Names: []string{by}})
		r.advanceLocked()
		return
	}
	r.clearRequestsLocked()
	r.refreshVideoLocked()
}

// positionLocked 是当前视频应在的位置。
func (r *Room) positionLocked() time.Duration {
	switch r.status {
	case StatusPlaying:
		return max(0, r.clock.Now().Sub(r.startAt))
	case StatusPaused:
		return r.video.pausePos
	}
	return 0
}

func (r *Room) addRequestLocked(userID, nickname, action string, positionMs int64) {
	r.dropRequestLocked(userID)
	v := &r.video
	if v.requests == nil {
		v.requests = map[string]*pendingRequest{}
	}
	v.reqSeq++
	id := v.reqSeq
	p := &pendingRequest{req: ControlRequest{
		ID: id, UserID: userID, Nickname: nickname, Action: action,
		ExpiresAt: r.clock.Now().Add(RequestTTL).UnixMilli(),
	}}
	if action == ActionSeek {
		p.req.PositionMs = max(positionMs, 0)
	}
	p.timer = r.clock.AfterFunc(RequestTTL, func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		if cur := r.video.requests[userID]; cur != p || r.closed {
			return
		}
		r.dropRequestLocked(userID)
		r.emit("control_result", map[string]any{"requestId": id, "userId": userID, "result": ResultExpired})
		r.refreshVideoLocked()
	})
	v.requests[userID] = p
	r.refreshVideoLocked()
}

func (r *Room) dropRequestLocked(userID string) {
	if p := r.video.requests[userID]; p != nil {
		if p.timer != nil {
			p.timer.Stop()
		}
		delete(r.video.requests, userID)
	}
}

// clearRequestsLocked：状态变了（有人控制了、换了视频），之前的请求都作废，不用再通知。
func (r *Room) clearRequestsLocked() {
	for id := range r.video.requests {
		r.dropRequestLocked(id)
	}
}

// ---- 消息流 ----

// Danmaku 发一条朋友弹幕。每人每秒最多一条，不受控制档位限制。
// nonce 是 App 给这条弹幕的编号，可以为空（旧版 App）；同一个人同一个编号只收一次，重复的直接当成功。
func (r *Room) Danmaku(userID, text, nonce string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, err := r.videoMemberLocked(userID)
	if err != nil {
		return err
	}
	text = strings.Join(strings.Fields(text), " ")
	if text == "" {
		return errCode(CodeBadRequest, "弹幕不能为空")
	}
	if utf8.RuneCountInString(text) > DanmakuMaxLen {
		return errCode(CodeBadRequest, fmt.Sprintf("弹幕最多 %d 个字", DanmakuMaxLen))
	}
	if nonce != "" && slices.Contains(r.video.danmakuNonces[userID], nonce) {
		return nil
	}
	now := r.clock.Now()
	if t := r.env.mutedUntil(userID, now); !t.IsZero() {
		return errCode(CodeForbidden, "你被管理员禁言了，"+muteLeft(t, now))
	}
	if r.video.lastDanmaku == nil {
		r.video.lastDanmaku = map[string]time.Time{}
	}
	if last, ok := r.video.lastDanmaku[userID]; ok && now.Sub(last) < DanmakuMinGap {
		return errCode(CodeRateLimited, "发得太快了")
	}
	r.video.lastDanmaku[userID] = now
	if nonce != "" {
		if r.video.danmakuNonces == nil {
			r.video.danmakuNonces = map[string][]string{}
		}
		ns := append(r.video.danmakuNonces[userID], nonce)
		if len(ns) > danmakuNonceKeep {
			ns = ns[len(ns)-danmakuNonceKeep:]
		}
		r.video.danmakuNonces[userID] = ns
	}
	r.addChatLocked(ChatMessage{Kind: ChatDanmaku, UserID: userID, Nickname: m.nickname, Text: text})
	return nil
}

// Mute 由管理员在放映厅里禁言或解除禁言（d == 0）。禁言是全服的：换个放映厅也发不了弹幕。
// 管理员之间不能互相禁言，要禁管理员得服主先取消他的管理员。
func (r *Room) Mute(by, target string, d time.Duration) error {
	r.mu.Lock()
	m, err := r.videoMemberLocked(by)
	if err != nil {
		r.mu.Unlock()
		return err
	}
	if !r.env.isAdmin(by) {
		r.mu.Unlock()
		return errCode(CodeForbidden, "只有管理员能禁言")
	}
	t, ok := r.members[target]
	switch {
	case !ok:
		r.mu.Unlock()
		return errCode(CodeNotFound, "这个人不在放映厅里")
	case target == by:
		r.mu.Unlock()
		return errCode(CodeBadRequest, "不能禁言自己")
	case r.env.isAdmin(target):
		r.mu.Unlock()
		return errCode(CodeForbidden, "不能禁言管理员")
	case r.env.SetMute == nil:
		r.mu.Unlock()
		return errCode(CodeBadRequest, "服务器不支持禁言")
	}
	byName, targetName := m.nickname, t.nickname
	now := r.clock.Now()
	r.mu.Unlock()

	var until time.Time
	switch {
	case d == MuteForever:
		until = time.Date(2100, 1, 1, 0, 0, 0, 0, time.UTC)
	case d > 0:
		until = now.Add(d)
	}
	// SetMute 会让所有放映厅重新广播，自己这间也在里面，所以不能拿着锁调。
	if err := r.env.SetMute(target, until); err != nil {
		return err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	switch {
	case until.IsZero():
		r.addSystemLocked(byName + "（管理员）解除了 " + targetName + " 的禁言")
	case d == MuteForever:
		r.addSystemLocked(byName + "（管理员）禁言了 " + targetName + "，直到解除")
	default:
		r.addSystemLocked(byName + "（管理员）禁言了 " + targetName + " " + formatSpan(d))
	}
	return nil
}

func muteLeft(until, now time.Time) string {
	if until.Year() >= 2100 {
		return "要等管理员解除"
	}
	return "还有 " + formatSpan(until.Sub(now)) + "解除"
}

func formatSpan(d time.Duration) string {
	m := int((d + time.Minute - 1) / time.Minute)
	if m >= 60 && m%60 == 0 {
		return fmt.Sprintf("%d 小时", m/60)
	}
	if m >= 60 {
		return fmt.Sprintf("%d 小时 %d 分钟", m/60, m%60)
	}
	return fmt.Sprintf("%d 分钟", m)
}

func (r *Room) addSystemLocked(text string) {
	r.addChatLocked(ChatMessage{Kind: ChatSystem, Text: text})
}

const danmakuNonceKeep = 8

func (r *Room) addChatLocked(msg ChatMessage) {
	v := &r.video
	v.chatSeq++
	msg.ID = v.chatSeq
	msg.At = r.clock.Now().UnixMilli()
	v.chat = append(v.chat, msg)
	if len(v.chat) > ChatMax {
		v.chat = append([]ChatMessage{}, v.chat[len(v.chat)-ChatMax:]...)
	}
	r.emit("chat", msg)
}

func (r *Room) videoMemberLocked(userID string) (*member, error) {
	m, ok := r.members[userID]
	if !ok {
		return nil, errCode(CodeNotInRoom, "")
	}
	if !r.isVideo() {
		return nil, errCode(CodeBadRequest, "只有放映厅能这样做")
	}
	return m, nil
}

func formatPos(d time.Duration) string {
	s := int(d / time.Second)
	if s >= 3600 {
		return fmt.Sprintf("%d:%02d:%02d", s/3600, s/60%60, s%60)
	}
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}
