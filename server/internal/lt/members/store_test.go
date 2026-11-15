package members

import (
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "lt.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func TestJoinAndAuthenticate(t *testing.T) {
	s := openTestStore(t)
	inv, err := s.CreateInvite(1, 0)
	if err != nil {
		t.Fatalf("create invite: %v", err)
	}

	m, token, err := s.Join(inv.Code, "小明")
	if err != nil {
		t.Fatalf("join: %v", err)
	}
	got, err := s.Authenticate(token)
	if err != nil || got.ID != m.ID {
		t.Fatalf("authenticate = %+v, %v; want member %s", got, err, m.ID)
	}

	if _, _, err := s.Join(inv.Code, "小红"); !errors.Is(err, ErrInviteInvalid) {
		t.Fatalf("second join on single-use invite: err = %v, want ErrInviteInvalid", err)
	}
}

func TestExpiredInviteAndDisabledMember(t *testing.T) {
	s := openTestStore(t)
	now := time.Date(2026, 10, 31, 12, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }

	inv, _ := s.CreateInvite(0, time.Hour)
	m, token, err := s.Join(inv.Code, "a")
	if err != nil {
		t.Fatalf("join: %v", err)
	}

	now = now.Add(2 * time.Hour)
	if _, _, err := s.Join(inv.Code, "b"); !errors.Is(err, ErrInviteInvalid) {
		t.Fatalf("join after expiry: err = %v, want ErrInviteInvalid", err)
	}

	if err := s.SetDisabled(m.ID, true); err != nil {
		t.Fatalf("disable: %v", err)
	}
	if _, err := s.Authenticate(token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("authenticate disabled member: err = %v, want ErrUnauthorized", err)
	}
}

func TestJoinRejectsBadNickname(t *testing.T) {
	s := openTestStore(t)
	inv, _ := s.CreateInvite(0, 0)
	if _, _, err := s.Join(inv.Code, "   "); !errors.Is(err, ErrBadNickname) {
		t.Fatalf("blank nickname: err = %v, want ErrBadNickname", err)
	}
}

func TestMutes(t *testing.T) {
	s := openTestStore(t)
	inv, _ := s.CreateInvite(0, 0)
	a, _, _ := s.Join(inv.Code, "a")
	b, _, _ := s.Join(inv.Code, "b")
	soon := time.Now().Add(time.Hour)
	past := time.Now().Add(-time.Minute)
	if err := s.SetMute(a.ID, &soon); err != nil {
		t.Fatal(err)
	}
	_ = s.SetMute(b.ID, &past)
	m, err := s.Mutes()
	if err != nil || len(m) != 1 || m[a.ID].Unix() != soon.Unix() {
		t.Fatalf("mutes = %v %v", m, err)
	}
	_ = s.SetMute(a.ID, nil)
	if m, _ := s.Mutes(); len(m) != 0 {
		t.Fatalf("after unmute = %v", m)
	}
}
