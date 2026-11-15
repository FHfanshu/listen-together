package room

import (
	"sort"
	"strings"
	"sync"
	"time"
)

type member struct {
	nickname string
	avatar   string
	joinedAt time.Time
	conns    int
	online   bool  // 断线 60 秒内仍算在线
	grace    Timer // 断线宽限计时
}

type Room struct {
	mu    sync.Mutex
	code  string
	kind  string
	env   *VideoEnv
	clock Clock
	out   Broadcaster
	// warm 在锁定下一首时异步调用，用于提前解析播放地址；返回错误则移除该歌。
	warm    func(TrackRef) error
	onEmpty func()

	owner   string
	members map[string]*member

	queue   []*QueueItem
	next    *QueueItem
	history []QueueItem

	status   string
	current  *QueueItem
	startAt  time.Time
	seq      int
	skippers map[string]bool
	likes    map[string]int
	lastLike map[string]time.Time
	ready    map[string]string

	activity []Activity
	actSeq   int

	rev     int
	itemSeq int

	lockT, endT, prepT, emptyT Timer
	closed                     bool

	video videoState
}

func newRoom(code string, clock Clock, out Broadcaster, warm func(TrackRef) error, onEmpty func()) *Room {
	return &Room{
		code:     code,
		clock:    clock,
		out:      out,
		warm:     warm,
		onEmpty:  onEmpty,
		members:  map[string]*member{},
		status:   StatusIdle,
		skippers: map[string]bool{},
		likes:    map[string]int{},
		lastLike: map[string]time.Time{},
		ready:    map[string]string{},
		kind:     KindMusic,
		env:      &VideoEnv{},
	}
}

func (r *Room) Code() string { return r.code }

func (r *Room) Kind() string { return r.kind }

func (r *Room) isVideo() bool { return r.kind == KindVideo }

// Attach 在成员的一条连接进房（或重连回房）时调用；新成员会被加入房间。
func (r *Room) Attach(userID, nickname, avatar string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, ok := r.members[userID]
	if !ok {
		if len(r.members) >= MaxMembers {
			return errCode(CodeRoomFull, "")
		}
		m = &member{joinedAt: r.clock.Now()}
		r.members[userID] = m
		if r.owner == "" {
			r.owner = userID
		}
	}
	m.nickname, m.avatar = nickname, avatar
	m.conns++
	if m.grace != nil {
		m.grace.Stop()
		m.grace = nil
	}
	m.online = true
	stopTimer(&r.emptyT)
	r.rev++
	r.emitMembers()
	r.refreshVideoLocked()
	return nil
}

// UpdateProfile 在成员改昵称或头像后调用，房间内立即广播新的成员列表。
func (r *Room) UpdateProfile(userID, nickname, avatar string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, ok := r.members[userID]
	if !ok || (m.nickname == nickname && m.avatar == avatar) {
		return
	}
	m.nickname, m.avatar = nickname, avatar
	r.rev++
	r.emitMembers()
}

// Detach 在连接断开时调用。成员保留 60 秒在线宽限，超时才算离线。
func (r *Room) Detach(userID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, ok := r.members[userID]
	if !ok || m.conns == 0 {
		return
	}
	m.conns--
	if m.conns > 0 {
		return
	}
	m.grace = r.clock.AfterFunc(OfflineGrace, func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		if m.conns > 0 || r.members[userID] != m {
			return
		}
		m.online = false
		m.grace = nil
		r.afterMemberGoneLocked(userID)
	})
}

// Leave 是成员主动退出：立即移出房间。他点的歌和投的票保留。
func (r *Room) Leave(userID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, ok := r.members[userID]
	if !ok {
		return
	}
	if m.grace != nil {
		m.grace.Stop()
	}
	delete(r.members, userID)
	r.afterMemberGoneLocked(userID)
}

func (r *Room) afterMemberGoneLocked(userID string) {
	delete(r.skippers, userID)
	delete(r.ready, userID)
	if r.owner == userID {
		r.transferOwnerLocked()
	}
	r.rev++
	r.emitMembers()
	r.refreshVideoLocked()
	if r.onlineCountLocked() == 0 {
		r.emptyT = r.clock.AfterFunc(EmptyTTL, func() {
			r.mu.Lock()
			empty := r.onlineCountLocked() == 0 && !r.closed
			if empty {
				r.closeLocked()
			}
			r.mu.Unlock()
			if empty && r.onEmpty != nil {
				r.onEmpty()
			}
		})
		return
	}
	// 在线人数变了，踩歌阈值和就绪等待都要重算。
	if (r.status == StatusPlaying || r.status == StatusPaused) && r.skipReachedLocked() {
		r.skipByVoteLocked()
		return
	}
	if r.status == StatusPreparing {
		r.checkReadyLocked()
	}
	if r.status == StatusPlaying || r.status == StatusPaused {
		r.emitPlayback()
	}
}

func (r *Room) transferOwnerLocked() {
	var best string
	var bestAt time.Time
	for id, m := range r.members {
		if !m.online || id == r.owner {
			continue
		}
		if best == "" || m.joinedAt.Before(bestAt) {
			best, bestAt = id, m.joinedAt
		}
	}
	if best != "" {
		r.owner = best
	} else if _, stillHere := r.members[r.owner]; !stillHere {
		r.owner = ""
	}
}

func (r *Room) AddSong(userID string, t TrackRef) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.members[userID]; !ok {
		return 0, errCode(CodeNotInRoom, "")
	}
	if t.DurationMs <= 0 {
		return 0, errCode(CodeBadRequest, "缺少歌曲时长")
	}
	if len(r.queue) >= QueueMax {
		return 0, errCode(CodeQueueFull, "")
	}

	r.itemSeq++
	item := &QueueItem{
		ItemID:      r.itemSeq,
		Track:       t,
		RequestedBy: userID,
		AddedAt:     r.clock.Now().UnixMilli(),
		VoterIDs:    []string{},
	}
	r.queue = append(r.queue, item)
	r.sortQueueLocked()
	r.rev++
	r.addActivityLocked(Activity{Kind: ActivityAdd, UserID: userID, Nickname: r.members[userID].nickname, Track: t})

	switch {
	case r.status == StatusIdle:
		r.advanceLocked()
	case r.status == StatusPlaying && r.next == nil && r.remainingLocked() <= LockBefore:
		r.lockNextLocked()
	default:
		r.emitQueue()
	}
	return item.ItemID, nil
}

func (r *Room) RemoveSong(userID string, itemID int) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.members[userID]; !ok {
		return errCode(CodeNotInRoom, "")
	}
	if r.current != nil && r.current.ItemID == itemID {
		return errCode(CodeItemLocked, "正在播放的歌只能踩")
	}
	if r.next != nil && r.next.ItemID == itemID {
		if r.next.RequestedBy != userID {
			return errCode(CodeForbidden, "")
		}
		r.next = nil
		r.rev++
		if r.status == StatusPlaying && r.remainingLocked() <= LockBefore {
			r.lockNextLocked()
		} else {
			r.emitQueue()
		}
		return nil
	}
	for i, it := range r.queue {
		if it.ItemID != itemID {
			continue
		}
		if it.RequestedBy != userID {
			return errCode(CodeForbidden, "")
		}
		r.queue = append(r.queue[:i], r.queue[i+1:]...)
		r.rev++
		r.emitQueue()
		return nil
	}
	return errCode(CodeNotFound, "")
}

func (r *Room) Vote(userID string, itemID int, on bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.members[userID]; !ok {
		return errCode(CodeNotInRoom, "")
	}
	if (r.current != nil && r.current.ItemID == itemID) || (r.next != nil && r.next.ItemID == itemID) {
		return errCode(CodeItemLocked, "")
	}
	for _, it := range r.queue {
		if it.ItemID != itemID {
			continue
		}
		has := false
		kept := it.VoterIDs[:0]
		for _, v := range it.VoterIDs {
			if v == userID {
				has = true
				if !on {
					continue
				}
			}
			kept = append(kept, v)
		}
		it.VoterIDs = kept
		if on && !has {
			it.VoterIDs = append(it.VoterIDs, userID)
		}
		if has != on {
			r.sortQueueLocked()
			r.rev++
			r.emitQueue()
		}
		return nil
	}
	return errCode(CodeNotFound, "")
}

// Skip 投票跳过当前歌。seq 不是当前值说明客户端看到的是上一首，直接忽略。
func (r *Room) Skip(userID string, seq int, on bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.members[userID]; !ok {
		return errCode(CodeNotInRoom, "")
	}
	if seq != r.seq || r.current == nil {
		return nil
	}
	if r.skippers[userID] == on {
		return nil
	}
	if on {
		r.skippers[userID] = true
	} else {
		delete(r.skippers, userID)
	}
	r.rev++
	if r.skipReachedLocked() {
		r.skipByVoteLocked()
		return nil
	}
	r.emitPlayback()
	return nil
}

// Like 给当前歌点赞，可以连点，每次都会广播给房间里的人放特效。
// 每人每首歌第一次点赞写进房间记录。返回当前歌的总赞数。
// Like 给当前歌加 count 个赞（App 连点时攒成一批发；旧版 App 不带 count，按 1 算）。
func (r *Room) Like(userID string, seq, count int) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m, ok := r.members[userID]
	if !ok {
		return 0, errCode(CodeNotInRoom, "")
	}
	if seq != r.seq || r.current == nil || (r.status != StatusPlaying && r.status != StatusPaused) {
		return r.totalLikesLocked(), nil
	}
	now := r.clock.Now()
	if now.Sub(r.lastLike[userID]) < LikeMinGap {
		return r.totalLikesLocked(), nil
	}
	count = min(max(count, 1), LikeBatchMax)
	first := r.likes[userID] == 0
	r.lastLike[userID] = now
	r.likes[userID] += count
	if first {
		r.addActivityLocked(Activity{Kind: ActivityLike, UserID: userID, Nickname: m.nickname, Track: r.current.Track})
	}
	total := r.totalLikesLocked()
	r.emit("like", map[string]any{"userId": userID, "seq": r.seq, "count": count, "likes": total, "likerIds": r.likerIDsLocked()})
	return total, nil
}

// TrackStatus 记录成员手机对当前歌的缓冲结果，全员上报后立即开播。
func (r *Room) TrackStatus(userID string, seq int, status string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.members[userID]; !ok {
		return errCode(CodeNotInRoom, "")
	}
	if status != TrackReady && status != TrackFailed {
		return errCode(CodeBadRequest, "status 只能是 ready 或 failed")
	}
	if seq != r.seq || r.status != StatusPreparing {
		return nil
	}
	r.ready[userID] = status
	r.checkReadyLocked()
	return nil
}

func (r *Room) Snapshot() (Snapshot, int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.snapshotLocked(), r.clock.Now().UnixMilli()
}

func (r *Room) Summary() Summary {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := Summary{RoomCode: r.code, Kind: r.kind, MemberCount: len(r.members), OnlineCount: r.onlineCountLocked()}
	if m, ok := r.members[r.owner]; ok {
		s.OwnerName = m.nickname
	}
	if r.current != nil {
		t := r.current.Track
		s.NowPlaying = &t
	}
	return s
}

func (r *Room) IsMember(userID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	_, ok := r.members[userID]
	return ok
}

func (r *Room) close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closeLocked()
}

func (r *Room) closeLocked() {
	r.closed = true
	stopTimer(&r.lockT)
	stopTimer(&r.endT)
	stopTimer(&r.prepT)
	stopTimer(&r.emptyT)
	for _, m := range r.members {
		if m.grace != nil {
			m.grace.Stop()
		}
	}
	r.clearRequestsLocked()
}

// ---- 播放推进 ----

// advanceLocked 结束当前歌，切到已锁定的下一首（没有就取排序第一），进入 preparing。
func (r *Room) advanceLocked() {
	stopTimer(&r.lockT)
	stopTimer(&r.endT)
	stopTimer(&r.prepT)
	if r.current != nil {
		r.history = append([]QueueItem{*r.current}, r.history...)
		if len(r.history) > HistoryMax {
			r.history = r.history[:HistoryMax]
		}
	}
	item := r.next
	r.next = nil
	if item == nil && len(r.queue) > 0 {
		item = r.queue[0]
		r.queue = r.queue[1:]
	}
	r.seq++
	r.skippers = map[string]bool{}
	r.likes = map[string]int{}
	r.lastLike = map[string]time.Time{}
	r.ready = map[string]string{}
	r.current = item
	r.startAt = time.Time{}
	r.video.pausePos = 0
	r.clearRequestsLocked()
	r.rev++
	if item == nil {
		r.status = StatusIdle
		r.emitPlayback()
		r.emitQueue()
		r.refreshVideoLocked()
		return
	}
	r.status = StatusPreparing
	if r.isVideo() {
		// 消息流里按视频分段：之后的弹幕和操作都属于这个视频。
		nick := ""
		if m, ok := r.members[item.RequestedBy]; ok {
			nick = m.nickname
		}
		r.addChatLocked(ChatMessage{Kind: ChatVideo, UserID: item.RequestedBy, Nickname: nick, Text: item.Track.Title})
	}
	seq := r.seq
	timeout := PrepareTimeout
	if r.isVideo() {
		timeout = VideoPrepareTimeout
	}
	r.prepT = r.clock.AfterFunc(timeout, func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.seq == seq && r.status == StatusPreparing && !r.closed {
			r.startLocked()
		}
	})
	r.emitPlayback()
	r.emitQueue()
	r.refreshVideoLocked()
	if r.onlineCountLocked() == 0 {
		r.startLocked()
	}
}

func (r *Room) checkReadyLocked() {
	online := 0
	failed := 0
	for id, m := range r.members {
		if !m.online {
			continue
		}
		online++
		switch r.ready[id] {
		case "":
			return
		case TrackFailed:
			failed++
		}
	}
	if online > 0 && failed == online {
		r.emitNotice("track_failed", "所有人都没能加载《"+r.current.Track.Title+"》，已跳过")
		r.advanceLocked()
		return
	}
	r.startLocked()
}

func (r *Room) startLocked() {
	stopTimer(&r.prepT)
	r.playFromLocked(0)
}

// playFromLocked 从 pos 开始播放（StartLead 之后出声），并按新的时间线重排结束和锁定下一首的计时器。
func (r *Room) playFromLocked(pos time.Duration) {
	stopTimer(&r.endT)
	stopTimer(&r.lockT)
	now := r.clock.Now()
	r.status = StatusPlaying
	r.startAt = now.Add(StartLead - pos)
	r.rev++
	dur := time.Duration(r.current.Track.DurationMs) * time.Millisecond
	seq := r.seq
	r.endT = r.clock.AfterFunc(r.startAt.Add(dur).Sub(now), func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.seq == seq && !r.closed {
			r.advanceLocked()
		}
	})
	lockIn := r.startAt.Add(dur - LockBefore).Sub(now)
	r.emitPlayback()
	if lockIn <= 0 {
		r.lockNextLocked()
		return
	}
	r.lockT = r.clock.AfterFunc(lockIn, func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.seq == seq && !r.closed {
			r.lockNextLocked()
		}
	})
}

func (r *Room) lockNextLocked() {
	if r.next != nil || len(r.queue) == 0 {
		r.emitQueue()
		return
	}
	r.next = r.queue[0]
	r.queue = r.queue[1:]
	r.rev++
	r.emitQueue()
	if r.warm == nil {
		return
	}
	item := r.next
	go func() {
		if err := r.warm(item.Track); err == nil {
			return
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.closed || r.next == nil || r.next.ItemID != item.ItemID {
			return
		}
		r.next = nil
		r.rev++
		r.emitNotice("track_unplayable", "《"+item.Track.Title+"》无法播放，已从歌单移除")
		if r.status == StatusPlaying && r.remainingLocked() <= LockBefore {
			r.lockNextLocked()
		} else {
			r.emitQueue()
		}
	}()
}

func (r *Room) skipByVoteLocked() {
	names := make([]string, 0, len(r.skippers))
	for id := range r.skippers {
		if m, ok := r.members[id]; ok {
			names = append(names, m.nickname)
		}
	}
	sort.Strings(names)
	r.addActivityLocked(Activity{Kind: ActivitySkip, Track: r.current.Track, Names: names})
	r.emitNotice("vote_skip", strings.Join(names, "、")+" 跳过了《"+r.current.Track.Title+"》")
	r.advanceLocked()
}

// skipReachedLocked：跳过票数达到 SkipNeed（在线不足 SkipNeed 人时为全部在线人数）就跳过。
func (r *Room) skipReachedLocked() bool {
	if r.current == nil || len(r.skippers) == 0 {
		return false
	}
	need := min(r.onlineCountLocked(), SkipNeed)
	return len(r.skippers) >= max(need, 1)
}

func (r *Room) totalLikesLocked() int {
	n := 0
	for _, c := range r.likes {
		n += c
	}
	return n
}

func (r *Room) likerIDsLocked() []string {
	ids := make([]string, 0, len(r.likes))
	for id := range r.likes {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

func (r *Room) addActivityLocked(a Activity) {
	r.actSeq++
	a.ID = r.actSeq
	a.At = r.clock.Now().UnixMilli()
	r.activity = append([]Activity{a}, r.activity...)
	if len(r.activity) > ActivityMax {
		r.activity = r.activity[:ActivityMax]
	}
	r.emit("activity", a)
}

func (r *Room) remainingLocked() time.Duration {
	if r.status != StatusPlaying || r.current == nil {
		return time.Duration(1<<62 - 1)
	}
	end := r.startAt.Add(time.Duration(r.current.Track.DurationMs) * time.Millisecond)
	return end.Sub(r.clock.Now())
}

func (r *Room) onlineCountLocked() int {
	n := 0
	for _, m := range r.members {
		if m.online {
			n++
		}
	}
	return n
}

// 票数多的在前；票数相同先加入的在前；同一毫秒按 itemId。
func (r *Room) sortQueueLocked() {
	sort.SliceStable(r.queue, func(i, j int) bool {
		a, b := r.queue[i], r.queue[j]
		if len(a.VoterIDs) != len(b.VoterIDs) {
			return len(a.VoterIDs) > len(b.VoterIDs)
		}
		if a.AddedAt != b.AddedAt {
			return a.AddedAt < b.AddedAt
		}
		return a.ItemID < b.ItemID
	})
}

// ---- 广播 ----

func (r *Room) snapshotLocked() Snapshot {
	s := Snapshot{
		RoomCode: r.code,
		Kind:     r.kind,
		OwnerID:  r.owner,
		Members:  r.membersViewLocked(),
		Playback: r.playbackLocked(),
		Queue:    make([]QueueItem, 0, len(r.queue)),
		History:  append([]QueueItem{}, r.history...),
		Activity: append([]Activity{}, r.activity...),
		Rev:      r.rev,
	}
	if r.next != nil {
		n := *r.next
		s.Next = &n
	}
	for _, it := range r.queue {
		s.Queue = append(s.Queue, *it)
	}
	if r.isVideo() {
		v := r.videoViewLocked()
		s.Video = &v
		s.Chat = append([]ChatMessage{}, r.video.chat...)
	}
	return s
}

func (r *Room) membersViewLocked() []MemberView {
	out := make([]MemberView, 0, len(r.members))
	for id, m := range r.members {
		out = append(out, MemberView{UserID: id, Nickname: m.nickname, Avatar: m.avatar, Online: m.online, JoinedAt: m.joinedAt.UnixMilli()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].JoinedAt < out[j].JoinedAt })
	return out
}

func (r *Room) playbackLocked() PlaybackState {
	p := PlaybackState{Status: r.status, Seq: r.seq, SkipperIDs: []string{}, Likes: r.totalLikesLocked(), LikerIDs: r.likerIDsLocked()}
	if r.current != nil {
		it := *r.current
		p.Item = &it
	}
	if r.status == StatusPlaying {
		ms := r.startAt.UnixMilli()
		p.StartAt = &ms
	}
	if r.status == StatusPaused {
		ms := r.video.pausePos.Milliseconds()
		p.PositionMs = &ms
	}
	for id := range r.skippers {
		p.SkipperIDs = append(p.SkipperIDs, id)
	}
	sort.Strings(p.SkipperIDs)
	return p
}

func (r *Room) emit(t string, data any) {
	if r.out != nil {
		r.out.Broadcast(r.code, Event{Type: t, Data: data})
	}
}

func (r *Room) emitQueue() {
	q := make([]QueueItem, 0, len(r.queue))
	for _, it := range r.queue {
		q = append(q, *it)
	}
	var next *QueueItem
	if r.next != nil {
		n := *r.next
		next = &n
	}
	r.emit("queue", map[string]any{"queue": q, "next": next, "rev": r.rev})
}

func (r *Room) emitPlayback() {
	r.emit("playback", map[string]any{"playback": r.playbackLocked(), "serverNowMs": r.clock.Now().UnixMilli(), "rev": r.rev})
}

func (r *Room) emitMembers() {
	r.emit("members", map[string]any{"members": r.membersViewLocked(), "ownerId": r.owner, "rev": r.rev})
}

func (r *Room) emitNotice(kind, text string) {
	r.emit("notice", map[string]any{"kind": kind, "text": text})
}

func stopTimer(t *Timer) {
	if *t != nil {
		(*t).Stop()
		*t = nil
	}
}
