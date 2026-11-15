package playurl

import (
	"errors"
	"testing"
	"time"

	"github.com/guohuiyuan/music-lib/model"
)

type fakeCDN struct {
	resolves   int
	total      int64
	needCookie bool
}

func newTestResolver(cdn *fakeCDN, now *time.Time) *Resolver {
	r := New()
	r.Now = func() time.Time { return *now }
	r.Resolve = func(song *model.Song, q Quality) (string, error) {
		cdn.resolves++
		return "https://cdn.example/" + song.ID + ".mp3", nil
	}
	r.Probe = func(url, source string, withCookie bool) (int64, error) {
		if cdn.needCookie && !withCookie {
			return 0, &StatusError{Code: 403}
		}
		return cdn.total, nil
	}
	return r
}

func song(id string, seconds int) *model.Song {
	return &model.Song{ID: id, Source: "netease", Duration: seconds}
}

func TestLookupReturnsDirectAndCaches(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cdn := &fakeCDN{total: 240 * 40_000} // 4 分钟 320kbps
	r := newTestResolver(cdn, &now)

	res, err := r.Lookup(song("1", 240), false)
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if res.Direct == nil || res.Direct.URL != "https://cdn.example/1.mp3" || res.DurationMs != 240_000 {
		t.Fatalf("unexpected result: %+v", res)
	}
	if _, ok := res.Direct.Headers["Cookie"]; ok {
		t.Fatal("direct headers must not carry the server cookie")
	}

	_, _ = r.Lookup(song("1", 240), false)
	if cdn.resolves != 1 {
		t.Fatalf("resolves = %d, want 1 (cached)", cdn.resolves)
	}
	now = now.Add(11 * time.Minute)
	_, _ = r.Lookup(song("1", 240), false)
	if cdn.resolves != 2 {
		t.Fatalf("resolves = %d, want 2 after TTL", cdn.resolves)
	}
}

func TestTrialClipIsUnplayable(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cdn := &fakeCDN{total: 30 * 40_000} // 只有 30 秒的文件，歌曲却有 4 分钟
	r := newTestResolver(cdn, &now)
	if _, err := r.Lookup(song("1", 240), false); !errors.Is(err, ErrUnplayable) {
		t.Fatalf("err = %v, want ErrUnplayable", err)
	}
}

func TestLowBitrateVoiceIsNotTrial(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cdn := &fakeCDN{total: 900 * 6_000} // 15 分钟、48kbps 的播客声音
	r := newTestResolver(cdn, &now)
	s := song("1", 900)
	s.Extra = map[string]string{VoiceExtra: "1"}
	if _, err := r.Lookup(s, false); err != nil {
		t.Fatalf("播客声音不该被当成试听片段: %v", err)
	}
}

func TestCookieOnlyCDNFallsBackToProxy(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cdn := &fakeCDN{total: 240 * 40_000, needCookie: true}
	r := newTestResolver(cdn, &now)
	res, err := r.Lookup(song("1", 240), false)
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if res.Direct != nil {
		t.Fatal("direct should be withheld when the CDN rejects cookieless requests")
	}

	r.Mode = ModeDirectOnly
	if _, err := r.Lookup(song("1", 240), false); !errors.Is(err, ErrUnplayable) {
		t.Fatalf("direct-only mode: err = %v, want ErrUnplayable", err)
	}
}

func TestDirectFailuresSwitchTrackThenPlatform(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cdn := &fakeCDN{total: 240 * 40_000}
	r := newTestResolver(cdn, &now)

	r.MarkDirectFailed("netease", "1")
	if res, _ := r.Lookup(song("1", 240), false); res.Direct != nil {
		t.Fatal("track 1 should be proxy-only after a direct failure")
	}
	if res, _ := r.Lookup(song("2", 240), false); res.Direct == nil {
		t.Fatal("track 2 should still be direct")
	}

	r.MarkDirectFailed("netease", "2")
	r.MarkDirectFailed("netease", "3")
	if res, _ := r.Lookup(song("4", 240), false); res.Direct != nil {
		t.Fatal("platform should be proxy-only after 3 failures")
	}
	now = now.Add(61 * time.Minute)
	if res, _ := r.Lookup(song("4", 240), false); res.Direct == nil {
		t.Fatal("platform proxy window should expire after an hour")
	}
}

func TestUnreachableCDNStillOffersDirect(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	r := newTestResolver(&fakeCDN{}, &now)
	r.Probe = func(url, source string, withCookie bool) (int64, error) {
		return 0, errors.New("context deadline exceeded")
	}
	res, err := r.Lookup(song("1", 240), false)
	if err != nil || res.Direct == nil {
		t.Fatalf("timeouts on the server side should still hand the phone a direct URL: res=%+v err=%v", res, err)
	}
}

func TestLosslessIsPerRequestAndFallsBack(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	cdn := &fakeCDN{total: 240 * 120_000}
	r := newTestResolver(cdn, &now)
	losslessOK := true
	r.Resolve = func(s *model.Song, q Quality) (string, error) {
		cdn.resolves++
		if q == QualityLossless {
			if !losslessOK {
				return "", errors.New("需要会员")
			}
			s.Ext = "flac"
			return "https://cdn.example/" + s.ID + ".flac", nil
		}
		s.Ext = "mp3"
		return "https://cdn.example/" + s.ID + ".mp3", nil
	}

	std, err := r.Lookup(song("1", 240), false)
	if err != nil || std.Quality != QualityStandard || std.Direct.URL != "https://cdn.example/1.mp3" {
		t.Fatalf("standard: %+v %v", std, err)
	}
	ll, err := r.LookupQuality(song("1", 240), false, QualityLossless)
	if err != nil || ll.Quality != QualityLossless || ll.Direct.URL != "https://cdn.example/1.flac" {
		t.Fatalf("lossless: %+v %v", ll, err)
	}
	// 两种音质分开缓存，选无损的人不影响别人拿到的 320k
	if again, _ := r.Lookup(song("1", 240), false); again.Direct.URL != std.Direct.URL || cdn.resolves != 2 {
		t.Fatalf("standard cache disturbed: %+v resolves=%d", again, cdn.resolves)
	}

	losslessOK = false
	fb, err := r.LookupQuality(song("2", 240), false, QualityLossless)
	if err != nil || fb.Quality != QualityStandard || fb.Direct.URL != "https://cdn.example/2.mp3" {
		t.Fatalf("fallback: %+v %v", fb, err)
	}
}

func TestKugouStreamExtraPicksHash(t *testing.T) {
	extra := map[string]string{"hash": "orig", "hq_hash": "hq", "sq_hash": "sq", "res_hash": "res"}
	if got := kugouStreamExtra(extra, false); got["hash"] != "hq" || got["sq_hash"] != "" || got["res_hash"] != "" {
		t.Fatalf("standard: %v", got)
	}
	if got := kugouStreamExtra(extra, true); got["hash"] != "sq" || got["res_hash"] != "" {
		t.Fatalf("lossless: %v", got)
	}
	if got := kugouStreamExtra(map[string]string{"hash": "orig", "hq_hash": "hq"}, true); got["hash"] != "hq" {
		t.Fatalf("lossless without sq_hash should fall back to 320k: %v", got)
	}
	if extra["hash"] != "orig" {
		t.Fatal("input map modified")
	}
}

func TestParseQuality(t *testing.T) {
	if ParseQuality("lossless") != QualityLossless || ParseQuality(" LOSSLESS ") != QualityLossless {
		t.Fatal("lossless not parsed")
	}
	if ParseQuality("") != QualityStandard || ParseQuality("hires") != QualityStandard {
		t.Fatal("unknown values must default to standard")
	}
}
