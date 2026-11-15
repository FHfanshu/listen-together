package videourl

import (
	"fmt"
	"testing"
	"time"
)

func sample(deadline int64) []byte {
	v := func(id, codec int, name string) string {
		return fmt.Sprintf(`{"id":%d,"baseUrl":"https://upos.example/%s.m4s?deadline=%d","codecid":%d,"codecs":"%s","width":1920,"height":1080}`, id, name, deadline, codec, name)
	}
	return []byte(`{"code":0,"data":{"timelength":600000,"accept_quality":[80,64,32],"accept_description":["高清 1080P","高清 720P","清晰 480P"],
	"dash":{"video":[` + v(80, 12, "1080hevc") + `,` + v(80, 7, "1080avc") + `,` + v(64, 7, "720avc") + `,` + v(32, 7, "480avc") + `],
	"audio":[{"id":30216,"baseUrl":"https://upos.example/a64.m4s"},{"id":30280,"baseUrl":"https://upos.example/a192.m4s?deadline=` + fmt.Sprint(deadline) + `"}]}}}`)
}

func TestLookupPicksQualityCodecAndExpiry(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	deadline := now.Add(2 * time.Hour).Unix()
	calls := 0
	r := New()
	r.Now = func() time.Time { return now }
	r.Fetch = func(bvid, cid string, qn int) ([]byte, error) {
		calls++
		if bvid != "BV1xx411c7mD" || cid != "123" {
			t.Fatalf("fetch %s %s", bvid, cid)
		}
		return sample(deadline), nil
	}

	res, err := r.Lookup("BV1xx411c7mD|123", 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Qn != 80 || res.Video.URL != fmt.Sprintf("https://upos.example/1080avc.m4s?deadline=%d", deadline) {
		t.Fatalf("video = %d %s, want 1080 AVC", res.Qn, res.Video.URL)
	}
	if res.Audio.URL == "" || res.Audio.Headers["Referer"] == "" {
		t.Fatalf("audio = %+v", res.Audio)
	}
	if want := time.Unix(deadline, 0).Add(-expirySlack).UnixMilli(); res.ExpiresAt != want {
		t.Fatalf("expiresAt = %d, want %d", res.ExpiresAt, want)
	}
	if len(res.Qualities) != 3 || res.Qualities[1].Label != "高清 720P" {
		t.Fatalf("qualities = %+v", res.Qualities)
	}

	// 走缓存。
	if _, err := r.Lookup("BV1xx411c7mD|123", 0, false); err != nil || calls != 1 {
		t.Fatalf("cache miss: calls=%d err=%v", calls, err)
	}
	// 代理画质：不超过 720P。
	res, _ = r.Lookup("BV1xx411c7mD|123", ProxyQn, false)
	if res.Qn != 64 {
		t.Fatalf("proxy qn = %d", res.Qn)
	}
	// 比最低档还低时取最低档。
	res, _ = r.Lookup("BV1xx411c7mD|123", Q360, false)
	if res.Qn != 32 {
		t.Fatalf("lowest qn = %d", res.Qn)
	}
}

func TestParseIDAndErrors(t *testing.T) {
	for _, bad := range []string{"", "BV1xx", "BV1xx|abc", "123|456"} {
		if _, _, err := ParseID(bad); err == nil {
			t.Fatalf("ParseID(%q) should fail", bad)
		}
	}
	if _, err := parse([]byte(`{"code":-404,"message":"啥都木有"}`), 80, time.Now()); err == nil {
		t.Fatal("expected error for code -404")
	}
	if _, err := parse([]byte(`{"code":0,"data":{}}`), 80, time.Now()); err == nil {
		t.Fatal("expected error without dash")
	}
}
