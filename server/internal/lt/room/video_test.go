package room

import (
	"strings"
	"testing"
	"time"
)

type videoEnvFake struct {
	admins map[string]bool
	policy string
	locked bool
	mutes  map[string]time.Time
}

func newVideoRoom(t *testing.T, env *videoEnvFake, users ...string) (*Room, *fakeClock, *recorder, *Manager) {
	t.Helper()
	clock := newFakeClock()
	rec := &recorder{}
	m := NewManager(clock, rec, nil)
	m.Video = VideoEnv{
		IsAdmin:    func(id string) bool { return env.admins[id] },
		Defaults:   func() (string, bool) { return env.policy, env.locked },
		MutedUntil: func(id string) time.Time { return env.mutes[id] },
		SetMute: func(id string, until time.Time) error {
			if env.mutes == nil {
				env.mutes = map[string]time.Time{}
			}
			env.mutes[id] = until
			m.VideoSettingsChanged()
			return nil
		},
	}
	r, err := m.CreateKind(KindVideo)
	if err != nil {
		t.Fatal(err)
	}
	for _, u := range users {
		if err := r.Attach(u, u, ""); err != nil {
			t.Fatal(err)
		}
		clock.advance(time.Millisecond)
	}
	return r, clock, rec, m
}

// startPlaying 让 user 点一个 600 秒的视频，所有人就绪后开播。
func startPlaying(t *testing.T, r *Room, clock *fakeClock, user string) int {
	t.Helper()
	mustAdd(t, r, user, track("v", 600))
	s, _ := r.Snapshot()
	for _, m := range s.Members {
		if m.Online {
			_ = r.TrackStatus(m.UserID, s.Playback.Seq, TrackReady)
		}
	}
	clock.advance(StartLead)
	s, _ = r.Snapshot()
	if s.Playback.Status != StatusPlaying {
		t.Fatalf("status = %s, want playing", s.Playback.Status)
	}
	return s.Playback.Seq
}

func systemTexts(r *Room) []string {
	s, _ := r.Snapshot()
	var out []string
	for _, c := range s.Chat {
		if c.Kind == ChatSystem {
			out = append(out, c.Text)
		}
	}
	return out
}

func lastSystem(t *testing.T, r *Room) string {
	t.Helper()
	all := systemTexts(r)
	if len(all) == 0 {
		t.Fatal("no system message")
	}
	return all[len(all)-1]
}

func TestVideoPauseSeekResumeTimeline(t *testing.T) {
	env := &videoEnvFake{policy: PolicyFree}
	r, clock, _, _ := newVideoRoom(t, env, "a", "b")
	seq := startPlaying(t, r, clock, "a")

	clock.advance(10 * time.Second)
	if req, err := r.Control("b", ActionPause, seq, 0); err != nil || req {
		t.Fatalf("pause: requested=%v err=%v", req, err)
	}
	s, _ := r.Snapshot()
	if s.Playback.Status != StatusPaused || s.Playback.PositionMs == nil || *s.Playback.PositionMs != 10_000 {
		t.Fatalf("after pause: %+v", s.Playback)
	}
	if s.Playback.StartAt != nil {
		t.Fatal("paused playback should have no startAt")
	}
	if got := lastSystem(t, r); got != "b 暂停了" {
		t.Fatalf("system = %q", got)
	}

	// 暂停期间不会因为时长到了就切走。
	clock.advance(20 * time.Minute)
	if s, _ := r.Snapshot(); s.Playback.Status != StatusPaused {
		t.Fatalf("status = %s after long pause", s.Playback.Status)
	}

	_, _ = r.Control("a", ActionSeek, seq, 125_000)
	s, _ = r.Snapshot()
	if *s.Playback.PositionMs != 125_000 || lastSystem(t, r) != "a 跳到了 2:05" {
		t.Fatalf("seek while paused: %+v %q", s.Playback, lastSystem(t, r))
	}

	_, _ = r.Control("a", ActionPlay, seq, 0)
	s, now := r.Snapshot()
	if s.Playback.Status != StatusPlaying || s.Playback.StartAt == nil {
		t.Fatalf("resume: %+v", s.Playback)
	}
	if pos := now - *s.Playback.StartAt; pos != 125_000-StartLead.Milliseconds() {
		t.Fatalf("position right after resume = %d", pos)
	}

	// 拖到结尾附近按 SeekEndGuard 截住，然后放完自动结束。
	_, _ = r.Control("a", ActionSeek, seq, 10_000_000)
	s, now = r.Snapshot()
	if pos := now - *s.Playback.StartAt + StartLead.Milliseconds(); pos != 599_000 {
		t.Fatalf("clamped seek target = %d", pos)
	}
	clock.advance(StartLead + 2*time.Second)
	if s, _ := r.Snapshot(); s.Playback.Status != StatusIdle {
		t.Fatalf("status after end = %s", s.Playback.Status)
	}
}

func TestVideoRequesterPolicyAndRequests(t *testing.T) {
	env := &videoEnvFake{policy: PolicyRequester, admins: map[string]bool{"admin": true}}
	r, clock, rec, _ := newVideoRoom(t, env, "a", "b", "admin")
	seq := startPlaying(t, r, clock, "a")

	// b 不是点播人：暂停变成请求。
	requested, err := r.Control("b", ActionPause, seq, 0)
	if err != nil || !requested {
		t.Fatalf("b pause: requested=%v err=%v", requested, err)
	}
	s, _ := r.Snapshot()
	if s.Playback.Status != StatusPlaying || len(s.Video.Requests) != 1 {
		t.Fatalf("request not pending: %+v %+v", s.Playback, s.Video)
	}
	reqID := s.Video.Requests[0].ID

	// 没权限的人不能处理请求。
	if err := r.Reply("b", reqID, true); codeOf(err) != CodeForbidden {
		t.Fatalf("b reply err = %v", err)
	}
	if err := r.Reply("a", reqID, true); err != nil {
		t.Fatal(err)
	}
	s, _ = r.Snapshot()
	if s.Playback.Status != StatusPaused || len(s.Video.Requests) != 0 {
		t.Fatalf("after accept: %+v %+v", s.Playback, s.Video)
	}
	if got := lastSystem(t, r); got != "a 同意了 b 的请求，暂停了" {
		t.Fatalf("system = %q", got)
	}

	// 管理员任何时候都能直接控制。
	if requested, _ := r.Control("admin", ActionPlay, seq, 0); requested {
		t.Fatal("admin should control directly")
	}

	// 拒绝：通知发起人，不公开。
	_, _ = r.Control("b", ActionSeek, seq, 30_000)
	s, _ = r.Snapshot()
	before := len(systemTexts(r))
	_ = r.Reply("admin", s.Video.Requests[0].ID, false)
	if len(systemTexts(r)) != before {
		t.Fatal("reject should not add public message")
	}
	if !hasResult(rec, "b", ResultRejected) {
		t.Fatal("no rejected result for b")
	}

	// 过期。
	_, _ = r.Control("b", ActionPause, seq, 0)
	clock.advance(RequestTTL)
	if s, _ := r.Snapshot(); len(s.Video.Requests) != 0 {
		t.Fatal("request should expire")
	}
	if !hasResult(rec, "b", ResultExpired) {
		t.Fatal("no expired result for b")
	}
}

func hasResult(rec *recorder, user, result string) bool {
	rec.mu.Lock()
	defer rec.mu.Unlock()
	for _, ev := range rec.events {
		if ev.Type != "control_result" {
			continue
		}
		d := ev.Data.(map[string]any)
		if d["userId"] == user && d["result"] == result {
			return true
		}
	}
	return false
}

func TestVideoSkipVoteWorksUnderEveryPolicy(t *testing.T) {
	env := &videoEnvFake{policy: PolicyAdmins, admins: map[string]bool{"admin": true}}
	r, clock, _, _ := newVideoRoom(t, env, "a", "b", "admin")
	seq := startPlaying(t, r, clock, "a")
	_ = r.Skip("a", seq, true)
	_ = r.Skip("b", seq, true)
	if s, _ := r.Snapshot(); s.Playback.Status != StatusIdle {
		t.Fatalf("skip vote should advance, status = %s", s.Playback.Status)
	}
}

func TestVideoFallbackWhenControllersOffline(t *testing.T) {
	env := &videoEnvFake{policy: PolicyRequester, admins: map[string]bool{"admin": true}}
	r, clock, _, _ := newVideoRoom(t, env, "a", "b", "admin")
	startPlaying(t, r, clock, "a")

	r.Leave("a")
	if s, _ := r.Snapshot(); s.Video.Effective != PolicyAdmins {
		t.Fatalf("effective = %s, want admins", s.Video.Effective)
	}
	if got := lastSystem(t, r); !strings.Contains(got, "暂由管理员") {
		t.Fatalf("system = %q", got)
	}

	r.Detach("admin")
	clock.advance(OfflineGrace)
	if s, _ := r.Snapshot(); s.Video.Effective != PolicyFree {
		t.Fatalf("effective = %s, want free", s.Video.Effective)
	}
	if got := lastSystem(t, r); !strings.Contains(got, "所有人都能控制") {
		t.Fatalf("system = %q", got)
	}

	_ = r.Attach("admin", "admin", "")
	if s, _ := r.Snapshot(); s.Video.Effective != PolicyAdmins {
		t.Fatalf("effective = %s after admin back", s.Video.Effective)
	}
}

func TestVideoSetPolicy(t *testing.T) {
	env := &videoEnvFake{policy: PolicyRequester, admins: map[string]bool{"admin": true}}
	r, _, _, m := newVideoRoom(t, env, "a", "admin")

	if err := r.SetPolicy("a", PolicyFree); codeOf(err) != CodeForbidden {
		t.Fatalf("non-admin set policy err = %v", err)
	}
	if err := r.SetPolicy("admin", PolicyFree); err != nil {
		t.Fatal(err)
	}
	if s, _ := r.Snapshot(); s.Video.Policy != PolicyFree {
		t.Fatalf("policy = %s", s.Video.Policy)
	}
	if got := lastSystem(t, r); got != "admin（管理员）把档位改成了「自由」" {
		t.Fatalf("system = %q", got)
	}

	// 服主锁定后统一用默认档位，管理员也不能改。
	env.locked = true
	m.VideoSettingsChanged()
	s, _ := r.Snapshot()
	if s.Video.Policy != PolicyRequester || !s.Video.Locked {
		t.Fatalf("locked view = %+v", s.Video)
	}
	if err := r.SetPolicy("admin", PolicyAdmins); codeOf(err) != CodeForbidden {
		t.Fatalf("set policy while locked err = %v", err)
	}
}

func TestDanmaku(t *testing.T) {
	env := &videoEnvFake{policy: PolicyRequester}
	r, clock, _, _ := newVideoRoom(t, env, "a")
	if err := r.Danmaku("a", "  前方  高能 ", ""); err != nil {
		t.Fatal(err)
	}
	if err := r.Danmaku("a", "太快", ""); codeOf(err) != CodeRateLimited {
		t.Fatalf("rate limit err = %v", err)
	}
	clock.advance(DanmakuMinGap)
	if err := r.Danmaku("a", strings.Repeat("字", DanmakuMaxLen+1), ""); codeOf(err) != CodeBadRequest {
		t.Fatalf("too long err = %v", err)
	}
	s, _ := r.Snapshot()
	if len(s.Chat) != 1 || s.Chat[0].Text != "前方 高能" || s.Chat[0].Kind != ChatDanmaku {
		t.Fatalf("chat = %+v", s.Chat)
	}
}

// 回执丢了 App 用同一个编号重发：不算太快，也不会多一条。
func TestDanmakuNonceDedup(t *testing.T) {
	env := &videoEnvFake{policy: PolicyRequester}
	r, clock, _, _ := newVideoRoom(t, env, "a")
	if err := r.Danmaku("a", "哈哈", "n1"); err != nil {
		t.Fatal(err)
	}
	if err := r.Danmaku("a", "哈哈", "n1"); err != nil {
		t.Fatalf("retry within gap err = %v", err)
	}
	clock.advance(DanmakuMinGap)
	if err := r.Danmaku("a", "哈哈", "n1"); err != nil {
		t.Fatalf("late retry err = %v", err)
	}
	if err := r.Danmaku("a", "哈哈", "n2"); err != nil {
		t.Fatalf("new nonce err = %v", err)
	}
	s, _ := r.Snapshot()
	if len(s.Chat) != 2 {
		t.Fatalf("chat = %+v", s.Chat)
	}
}

func TestChatMarksEachNewVideo(t *testing.T) {
	env := &videoEnvFake{policy: PolicyFree}
	r, clock, _, _ := newVideoRoom(t, env, "a")
	seq := startPlaying(t, r, clock, "a")
	mustAdd(t, r, "a", track("w", 300))
	_, _ = r.Control("a", ActionNext, seq, 0)
	s, _ := r.Snapshot()
	var dividers []string
	for _, c := range s.Chat {
		if c.Kind == ChatVideo {
			if c.Nickname != "a" {
				t.Fatalf("divider nickname = %q", c.Nickname)
			}
			dividers = append(dividers, c.Text)
		}
	}
	if len(dividers) != 2 || dividers[0] != "song v" || dividers[1] != "song w" {
		t.Fatalf("dividers = %v", dividers)
	}
}

func TestMusicRoomRejectsVideoOps(t *testing.T) {
	r, _, _ := newTestRoom(t, "a")
	if _, err := r.Control("a", ActionPause, 0, 0); codeOf(err) != CodeBadRequest {
		t.Fatalf("control in music room err = %v", err)
	}
	if err := r.Danmaku("a", "hi", ""); codeOf(err) != CodeBadRequest {
		t.Fatalf("danmaku in music room err = %v", err)
	}
	if s, _ := r.Snapshot(); s.Video != nil || s.Kind != KindMusic {
		t.Fatalf("music snapshot has video fields: %+v", s.Video)
	}
}

func TestMute(t *testing.T) {
	env := &videoEnvFake{policy: PolicyRequester, admins: map[string]bool{"adm": true, "adm2": true}}
	r, clock, _, _ := newVideoRoom(t, env, "adm", "adm2", "b")
	if err := r.Mute("b", "adm", 10*time.Minute); codeOf(err) != CodeForbidden {
		t.Fatalf("non-admin mute err = %v", err)
	}
	if err := r.Mute("adm", "adm2", 10*time.Minute); codeOf(err) != CodeForbidden {
		t.Fatalf("mute admin err = %v", err)
	}
	if err := r.Mute("adm", "b", 10*time.Minute); err != nil {
		t.Fatal(err)
	}
	if err := r.Danmaku("b", "hi", ""); codeOf(err) != CodeForbidden {
		t.Fatalf("muted danmaku err = %v", err)
	}
	s, _ := r.Snapshot()
	if s.Video.Muted["b"] == 0 || !strings.Contains(s.Chat[len(s.Chat)-1].Text, "禁言了 b 10 分钟") {
		t.Fatalf("video = %+v chat = %+v", s.Video, s.Chat)
	}
	// 到期自动解除。
	clock.advance(10 * time.Minute)
	if err := r.Danmaku("b", "hi", ""); err != nil {
		t.Fatalf("after expiry err = %v", err)
	}
	clock.advance(DanmakuMinGap)
	if err := r.Mute("adm", "b", MuteForever); err != nil {
		t.Fatal(err)
	}
	if err := r.Danmaku("b", "hi", ""); codeOf(err) != CodeForbidden {
		t.Fatalf("forever err = %v", err)
	}
	if err := r.Mute("adm", "b", 0); err != nil {
		t.Fatal(err)
	}
	if err := r.Danmaku("b", "hi2", ""); err != nil {
		t.Fatalf("unmuted err = %v", err)
	}
}
