package room

import (
	"errors"
	"sort"
	"sync"
	"testing"
	"time"
)

// fakeClock 按时间顺序同步触发计时器，测试里用 advance 推进时间。
type fakeClock struct {
	mu     sync.Mutex
	now    time.Time
	timers []*fakeTimer
}

type fakeTimer struct {
	at      time.Time
	f       func()
	stopped bool
}

func (t *fakeTimer) Stop() bool {
	was := !t.stopped
	t.stopped = true
	return was
}

func newFakeClock() *fakeClock {
	return &fakeClock{now: time.Date(2026, 10, 31, 20, 0, 0, 0, time.UTC)}
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) AfterFunc(d time.Duration, f func()) Timer {
	c.mu.Lock()
	defer c.mu.Unlock()
	t := &fakeTimer{at: c.now.Add(d), f: f}
	c.timers = append(c.timers, t)
	return t
}

func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	target := c.now.Add(d)
	c.mu.Unlock()
	for {
		c.mu.Lock()
		sort.SliceStable(c.timers, func(i, j int) bool { return c.timers[i].at.Before(c.timers[j].at) })
		var due *fakeTimer
		for i, t := range c.timers {
			if t.stopped {
				continue
			}
			if !t.at.After(target) {
				due = t
				c.timers = append(c.timers[:i], c.timers[i+1:]...)
			}
			break
		}
		if due == nil {
			c.timers = compact(c.timers)
			c.now = target
			c.mu.Unlock()
			return
		}
		c.now = due.at
		c.mu.Unlock()
		due.f()
	}
}

func compact(ts []*fakeTimer) []*fakeTimer {
	out := ts[:0]
	for _, t := range ts {
		if !t.stopped {
			out = append(out, t)
		}
	}
	return out
}

type recorder struct {
	mu     sync.Mutex
	events []Event
}

func (r *recorder) Broadcast(_ string, ev Event) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.events = append(r.events, ev)
}

func (r *recorder) notices() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for _, ev := range r.events {
		if ev.Type == "notice" {
			out = append(out, ev.Data.(map[string]any)["kind"].(string))
		}
	}
	return out
}

func newTestRoom(t *testing.T, users ...string) (*Room, *fakeClock, *recorder) {
	t.Helper()
	clock := newFakeClock()
	rec := &recorder{}
	m := NewManager(clock, rec, nil)
	r, err := m.Create()
	if err != nil {
		t.Fatal(err)
	}
	for _, u := range users {
		if err := r.Attach(u, u, ""); err != nil {
			t.Fatalf("attach %s: %v", u, err)
		}
		clock.advance(time.Millisecond) // 让进房时间有先后
	}
	return r, clock, rec
}

func track(id string, seconds int) TrackRef {
	return TrackRef{Source: "netease", ID: id, Title: "song " + id, Artists: []string{"artist " + id}, DurationMs: int64(seconds) * 1000}
}

func mustAdd(t *testing.T, r *Room, user string, tr TrackRef) int {
	t.Helper()
	id, err := r.AddSong(user, tr)
	if err != nil {
		t.Fatalf("add %s by %s: %v", tr.ID, user, err)
	}
	return id
}

func codeOf(err error) string {
	var e *Error
	if errors.As(err, &e) {
		return e.Code
	}
	return ""
}

func queueIDs(r *Room) []string {
	s, _ := r.Snapshot()
	out := []string{}
	for _, it := range s.Queue {
		out = append(out, it.Track.ID)
	}
	return out
}

func TestFirstSongStartsAfterEveryoneReady(t *testing.T) {
	r, clock, _ := newTestRoom(t, "a", "b")
	mustAdd(t, r, "a", track("1", 200))

	s, _ := r.Snapshot()
	if s.Playback.Status != StatusPreparing || s.Playback.Item.Track.ID != "1" || s.Playback.StartAt != nil {
		t.Fatalf("after first add: %+v", s.Playback)
	}
	seq := s.Playback.Seq
	_ = r.TrackStatus("a", seq, TrackReady)
	if s, _ := r.Snapshot(); s.Playback.Status != StatusPreparing {
		t.Fatal("should keep waiting for b")
	}
	_ = r.TrackStatus("b", seq, TrackReady)
	s, now := r.Snapshot()
	if s.Playback.Status != StatusPlaying || *s.Playback.StartAt != now+StartLead.Milliseconds() {
		t.Fatalf("after all ready: %+v (now %d)", s.Playback, now)
	}
	_ = clock
}

func TestPrepareTimesOutAfterThreeSeconds(t *testing.T) {
	r, clock, _ := newTestRoom(t, "a", "b")
	mustAdd(t, r, "a", track("1", 200))
	clock.advance(PrepareTimeout)
	if s, _ := r.Snapshot(); s.Playback.Status != StatusPlaying {
		t.Fatalf("status = %s, want playing after timeout", s.Playback.Status)
	}
}

func TestQueueSortsByVotesThenTime(t *testing.T) {
	r, clock, _ := newTestRoom(t, "a", "b", "c")
	mustAdd(t, r, "a", track("1", 200)) // 直接开始播放
	mustAdd(t, r, "a", track("2", 200))
	clock.advance(time.Millisecond)
	mustAdd(t, r, "b", track("3", 200))
	clock.advance(time.Millisecond)
	id4 := mustAdd(t, r, "b", track("4", 200))

	if got := queueIDs(r); len(got) != 3 || got[0] != "2" || got[2] != "4" {
		t.Fatalf("initial order = %v", got)
	}
	if err := r.Vote("a", id4, true); err != nil {
		t.Fatal(err)
	}
	if got := queueIDs(r); got[0] != "4" {
		t.Fatalf("after vote, order = %v, want 4 first", got)
	}
	// 自己点的歌也能投
	if err := r.Vote("b", id4, true); err != nil {
		t.Fatalf("self vote err = %v", err)
	}
	_ = r.Vote("a", id4, false)
	_ = r.Vote("b", id4, false)
	if got := queueIDs(r); got[0] != "2" {
		t.Fatalf("after unvote, order = %v, want 2 first", got)
	}
}

func TestNextIsLockedFifteenSecondsBeforeEnd(t *testing.T) {
	r, clock, _ := newTestRoom(t, "a", "b")
	mustAdd(t, r, "a", track("1", 60))
	clock.advance(PrepareTimeout)
	id2 := mustAdd(t, r, "a", track("2", 60))
	id3 := mustAdd(t, r, "b", track("3", 60))

	// 开播在 StartLead 之后，歌长 60 秒，所以第 45.8 秒时锁定。
	clock.advance(StartLead + 44*time.Second)
	if s, _ := r.Snapshot(); s.Next != nil {
		t.Fatal("locked too early")
	}
	clock.advance(time.Second)
	s, _ := r.Snapshot()
	if s.Next == nil || s.Next.ItemID != id2 {
		t.Fatalf("next = %+v, want item %d", s.Next, id2)
	}
	if err := r.Vote("b", id2, true); codeOf(err) != CodeItemLocked {
		t.Fatalf("vote on locked item err = %v", err)
	}
	// 锁定后再投票不影响下一首。
	_ = r.Vote("a", id3, true)

	clock.advance(15 * time.Second)
	s, _ = r.Snapshot()
	if s.Playback.Status != StatusPreparing || s.Playback.Item.ItemID != id2 || len(s.History) != 1 {
		t.Fatalf("after end: %+v history=%d", s.Playback, len(s.History))
	}
}

func TestAddingIntoLockWindowLocksImmediately(t *testing.T) {
	r, clock, _ := newTestRoom(t, "a")
	mustAdd(t, r, "a", track("1", 30))
	clock.advance(PrepareTimeout)
	clock.advance(20 * time.Second) // 剩约 10 秒，queue 为空所以没锁
	id := mustAdd(t, r, "a", track("2", 30))
	if s, _ := r.Snapshot(); s.Next == nil || s.Next.ItemID != id {
		t.Fatal("song added inside the lock window should become next immediately")
	}
}

func TestSkipNeedsTwoVotes(t *testing.T) {
	r, _, rec := newTestRoom(t, "a", "b", "c", "d")
	mustAdd(t, r, "a", track("1", 200))
	mustAdd(t, r, "b", track("2", 200))
	s, _ := r.Snapshot()
	seq := s.Playback.Seq

	_ = r.Skip("b", seq, true)
	if s, _ := r.Snapshot(); s.Playback.Item.Track.ID != "1" || len(s.Playback.SkipperIDs) != 1 {
		t.Fatal("1 skip vote should not skip")
	}
	_ = r.Skip("c", seq-1, true) // 旧 seq 被忽略
	_ = r.Skip("c", seq, true)
	s, _ = r.Snapshot()
	if s.Playback.Item.Track.ID != "2" || s.Playback.Seq != seq+1 {
		t.Fatalf("2 skip votes should skip to 2, got %+v", s.Playback)
	}
	if n := rec.notices(); len(n) != 1 || n[0] != "vote_skip" {
		t.Fatalf("notices = %v", n)
	}
	if a := s.Activity[0]; a.Kind != ActivitySkip || a.Track.ID != "1" || len(a.Names) != 2 || a.Names[0] != "b" || a.Names[1] != "c" {
		t.Fatalf("skip activity = %+v", a)
	}
}

func TestSkipAloneNeedsOneVote(t *testing.T) {
	r, _, _ := newTestRoom(t, "a")
	mustAdd(t, r, "a", track("1", 200))
	mustAdd(t, r, "a", track("2", 200))
	s, _ := r.Snapshot()
	_ = r.Skip("a", s.Playback.Seq, true)
	if s, _ := r.Snapshot(); s.Playback.Item.Track.ID != "2" {
		t.Fatal("a single listener should skip with one vote")
	}
}

func TestLikesCountAndLogOncePerUser(t *testing.T) {
	r, clock, rec := newTestRoom(t, "a", "b")
	mustAdd(t, r, "a", track("1", 200))
	s, _ := r.Snapshot()
	seq := s.Playback.Seq
	if n, _ := r.Like("b", seq, 1); n != 0 {
		t.Fatal("liking before playback starts should be ignored")
	}
	clock.advance(PrepareTimeout + StartLead + time.Second)

	n1, _ := r.Like("b", seq, 0) // 旧版 App 不带 count，按 1 算
	n2, _ := r.Like("b", seq, 5) // 两批隔得太近，忽略
	clock.advance(LikeMinGap)
	n3, _ := r.Like("b", seq, 4) // 一批攒了 4 个
	clock.advance(LikeMinGap)
	n4, _ := r.Like("a", seq, 1000) // 一批最多算 LikeBatchMax
	if n1 != 1 || n2 != 1 || n3 != 5 || n4 != 5+LikeBatchMax {
		t.Fatalf("likes = %d %d %d %d", n1, n2, n3, n4)
	}
	// 不设单人上限
	for i := 0; i < 3; i++ {
		clock.advance(LikeMinGap)
		r.Like("b", seq, LikeBatchMax)
	}
	s, _ = r.Snapshot()
	if s.Playback.Likes != 5+4*LikeBatchMax || len(s.Playback.LikerIDs) != 2 {
		t.Fatalf("playback likes = %+v", s.Playback)
	}
	kinds := []string{}
	for _, a := range s.Activity {
		kinds = append(kinds, a.Kind+":"+a.Nickname)
	}
	// 新的在前：a 点赞、b 点赞（只记第一次）、a 点歌
	if len(kinds) != 3 || kinds[0] != "like:a" || kinds[1] != "like:b" || kinds[2] != "add:a" {
		t.Fatalf("activity = %v", kinds)
	}
	likeEvents := 0
	rec.mu.Lock()
	for _, ev := range rec.events {
		if ev.Type == "like" {
			likeEvents++
		}
	}
	rec.mu.Unlock()
	if likeEvents != 6 {
		t.Fatalf("like events = %d", likeEvents)
	}
	// 换歌后赞数清零
	mustAdd(t, r, "b", track("2", 200))
	_ = r.Skip("a", seq, true)
	_ = r.Skip("b", seq, true)
	if s, _ := r.Snapshot(); s.Playback.Likes != 0 || len(s.Playback.LikerIDs) != 0 {
		t.Fatalf("likes should reset on next song: %+v", s.Playback)
	}
}

func TestDuplicateAndLimits(t *testing.T) {
	r, _, _ := newTestRoom(t, "a", "b")
	mustAdd(t, r, "a", track("1", 200))
	// 同一首歌可以重复点（包括正在播的）
	mustAdd(t, r, "b", track("1", 200))

	// 不限每人点几首，只受整个歌单上限约束
	for len(queueIDs(r)) < QueueMax {
		mustAdd(t, r, "b", track("x", 200))
	}
	if _, err := r.AddSong("b", track("z", 200)); codeOf(err) != CodeQueueFull {
		t.Fatalf("queue full err = %v, want QUEUE_FULL", err)
	}
}

func TestRemovePermissions(t *testing.T) {
	r, _, _ := newTestRoom(t, "owner", "b", "c")
	cur := mustAdd(t, r, "b", track("1", 200))
	id2 := mustAdd(t, r, "b", track("2", 200))
	id3 := mustAdd(t, r, "b", track("3", 200))

	if err := r.RemoveSong("b", cur); codeOf(err) != CodeItemLocked {
		t.Fatalf("remove current err = %v", err)
	}
	if err := r.RemoveSong("c", id2); codeOf(err) != CodeForbidden {
		t.Fatalf("remove other's song err = %v", err)
	}
	if err := r.RemoveSong("b", id2); err != nil {
		t.Fatal(err)
	}
	// 房主没有删别人歌的特权，只有点歌人自己能删。
	if err := r.RemoveSong("owner", id3); codeOf(err) != CodeForbidden {
		t.Fatalf("owner remove other's song err = %v, want FORBIDDEN", err)
	}
	if err := r.RemoveSong("b", id3); err != nil {
		t.Fatal(err)
	}
	if got := queueIDs(r); len(got) != 0 {
		t.Fatalf("queue = %v, want empty", got)
	}
}

func TestOwnerTransferAfterGraceAndEmptyRoomCloses(t *testing.T) {
	clock := newFakeClock()
	m := NewManager(clock, &recorder{}, nil)
	r, _ := m.Create()
	_ = r.Attach("a", "a", "")
	clock.advance(time.Millisecond)
	_ = r.Attach("b", "b", "")

	r.Detach("a")
	clock.advance(OfflineGrace - time.Second)
	if s, _ := r.Snapshot(); s.OwnerID != "a" {
		t.Fatal("owner changed during grace period")
	}
	clock.advance(time.Second)
	if s, _ := r.Snapshot(); s.OwnerID != "b" {
		t.Fatalf("owner = %s, want b after grace", s.OwnerID)
	}

	r.Detach("b")
	clock.advance(OfflineGrace + EmptyTTL)
	if _, ok := m.Get(r.Code()); ok {
		t.Fatal("empty room should be removed after EmptyTTL")
	}
}

func TestOfflineMemberNotCountedForReadiness(t *testing.T) {
	r, clock, _ := newTestRoom(t, "a", "b")
	r.Detach("b")
	clock.advance(OfflineGrace)
	mustAdd(t, r, "a", track("1", 200))
	s, _ := r.Snapshot()
	_ = r.TrackStatus("a", s.Playback.Seq, TrackReady)
	if s, _ := r.Snapshot(); s.Playback.Status != StatusPlaying {
		t.Fatal("offline member should not block start")
	}
}

func TestAllFailedSkips(t *testing.T) {
	r, _, rec := newTestRoom(t, "a", "b")
	mustAdd(t, r, "a", track("1", 200))
	mustAdd(t, r, "a", track("2", 200))
	s, _ := r.Snapshot()
	_ = r.TrackStatus("a", s.Playback.Seq, TrackFailed)
	_ = r.TrackStatus("b", s.Playback.Seq, TrackFailed)
	s, _ = r.Snapshot()
	if s.Playback.Item.Track.ID != "2" || s.Playback.Status != StatusPreparing {
		t.Fatalf("after all failed: %+v", s.Playback)
	}
	if n := rec.notices(); len(n) != 1 || n[0] != "track_failed" {
		t.Fatalf("notices = %v", n)
	}
}

func TestWarmFailureRemovesLockedSong(t *testing.T) {
	clock := newFakeClock()
	rec := &recorder{}
	done := make(chan struct{})
	m := NewManager(clock, rec, func(tr TrackRef) error {
		defer close(done)
		if tr.ID == "bad" {
			return errors.New("gone")
		}
		return nil
	})
	r, _ := m.Create()
	_ = r.Attach("a", "a", "")
	mustAdd(t, r, "a", track("1", 20))
	clock.advance(PrepareTimeout)
	mustAdd(t, r, "a", track("bad", 200))
	clock.advance(6 * time.Second) // 进入最后 15 秒，锁定并触发 warm
	<-done
	// warm 的回调在另一个 goroutine 里加锁处理，等它完成。
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if s, _ := r.Snapshot(); s.Next == nil && len(s.Queue) == 0 {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("unplayable locked song should be removed")
}

func TestRoomFull(t *testing.T) {
	r, _, _ := newTestRoom(t)
	for i := 0; i < MaxMembers; i++ {
		if err := r.Attach(string(rune('a'+i)), "x", ""); err != nil {
			t.Fatal(err)
		}
	}
	if err := r.Attach("late", "x", ""); codeOf(err) != CodeRoomFull {
		t.Fatalf("11th member err = %v", err)
	}
}
