package appapi

import (
	"errors"
	"testing"
)

func TestParseBilibiliRef(t *testing.T) {
	noExpand := func(string) (string, error) { t.Fatal("不该展开短链"); return "", nil }
	cases := []struct {
		in   string
		want bilibiliRef
	}{
		{"BV1GJ411x7h7", bilibiliRef{"BV1GJ411x7h7", 0}},
		{"https://www.bilibili.com/video/BV1GJ411x7h7/?p=3&spm_id_from=333.788", bilibiliRef{"BV1GJ411x7h7", 3}},
		{"https://m.bilibili.com/video/BV1GJ411x7h7?share_source=copy_web", bilibiliRef{"BV1GJ411x7h7", 0}},
		{"  bv1GJ411x7h7  BV1GJ411x7h7 ", bilibiliRef{"BV1GJ411x7h7", 0}},
	}
	for _, c := range cases {
		got, err := parseBilibiliRef(c.in, noExpand)
		if err != nil || got != c.want {
			t.Errorf("%q: got %+v, %v; want %+v", c.in, got, err, c.want)
		}
	}
}

func TestParseBilibiliRefShortLink(t *testing.T) {
	share := "【Never Gonna Give You Up】 https://b23.tv/abcDEF1"
	var asked string
	expand := func(u string) (string, error) {
		asked = u
		return "https://www.bilibili.com/video/BV1GJ411x7h7?p=2&share_medium=android", nil
	}
	got, err := parseBilibiliRef(share, expand)
	if err != nil || got != (bilibiliRef{"BV1GJ411x7h7", 2}) {
		t.Fatalf("got %+v, %v", got, err)
	}
	if asked != "https://b23.tv/abcDEF1" {
		t.Errorf("展开的短链是 %q", asked)
	}

	_, err = parseBilibiliRef("https://b23.tv/live123", func(string) (string, error) {
		return "https://live.bilibili.com/123", nil
	})
	if err == nil {
		t.Error("直播短链应该报错")
	}
}

func TestParseBilibiliRefKeyword(t *testing.T) {
	_, err := parseBilibiliRef("周杰伦 晴天", func(string) (string, error) { return "", nil })
	if !errors.Is(err, errBilibiliNeedLink) {
		t.Errorf("关键词应该提示粘贴链接，got %v", err)
	}
}

func testView(parts ...string) *bilibiliView {
	v := &bilibiliView{BVID: "BV1xx411c7mD", Title: "合唱合集", Pic: "http://i0.hdslb.com/a.jpg"}
	v.Owner.Name = "某UP"
	for i, p := range parts {
		v.Pages = append(v.Pages, struct {
			CID      int64  `json:"cid"`
			Part     string `json:"part"`
			Duration int    `json:"duration"`
		}{int64(100 + i), p, 60 * (i + 1)})
	}
	return v
}

func TestBilibiliPageSongs(t *testing.T) {
	single := bilibiliPageSongs(testView("合唱合集 - 正片"), 0)
	if len(single) != 1 || single[0].Name != "合唱合集" || single[0].ID != "BV1xx411c7mD|100" {
		t.Fatalf("单P: %+v", single)
	}
	if single[0].Cover != "https://i0.hdslb.com/a.jpg" || single[0].Extra["cid"] != "100" {
		t.Errorf("封面或 cid 不对: %+v", single[0])
	}

	multi := bilibiliPageSongs(testView("第一首", "", "第三首"), 0)
	names := []string{multi[0].Name, multi[1].Name, multi[2].Name}
	if names[0] != "P1 第一首" || names[1] != "P2 合唱合集" || names[2] != "P3 第三首" {
		t.Errorf("多P标题: %v", names)
	}
	if multi[2].Album != "合唱合集" || multi[2].Duration != 180 {
		t.Errorf("专辑或时长不对: %+v", multi[2])
	}

	picked := bilibiliPageSongs(testView("一", "二", "三", "四"), 3)
	order := []string{picked[0].Name, picked[1].Name, picked[2].Name, picked[3].Name}
	want := []string{"P3 三", "P1 一", "P2 二", "P4 四"}
	for i := range want {
		if order[i] != want[i] {
			t.Fatalf("指定 p=3 后顺序: %v", order)
		}
	}
	if out := bilibiliPageSongs(testView("一", "二"), 9); out[0].Name != "P1 一" {
		t.Errorf("越界的 p 应该忽略: %v", out[0].Name)
	}
}

func TestLooksLikeBilibili(t *testing.T) {
	for _, in := range []string{
		"【《码头姑娘》-全三个版本-哔哩哔哩】 https://b23.tv/KsJIV34",
		"https://www.bilibili.com/video/BV1BV411S7Kh?p=2",
		"BV1BV411S7Kh",
	} {
		if !looksLikeBilibili(in) {
			t.Errorf("%q 应该认成 B 站", in)
		}
	}
	for _, in := range []string{"周杰伦 晴天", "https://music.163.com/song?id=1", "bilibili 晴天"} {
		if looksLikeBilibili(in) {
			t.Errorf("%q 不该认成 B 站", in)
		}
	}
}
