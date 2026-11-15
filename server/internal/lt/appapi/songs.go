package appapi

import (
	"container/list"
	"strings"
	"sync"

	"github.com/guohuiyuan/music-lib/model"

	"github.com/guohuiyuan/go-music-dl/internal/lt/room"
)

// songCache 记住搜索结果里的完整 model.Song。点歌和解析地址只带 source:id，
// 某些平台解析时还要用到 Extra 等搜索返回的字段，所以必须从这里取回原始对象。
type songCache struct {
	mu    sync.Mutex
	max   int
	ll    *list.List
	items map[string]*list.Element
}

func newSongCache(max int) *songCache {
	return &songCache{max: max, ll: list.New(), items: map[string]*list.Element{}}
}

func (c *songCache) put(s model.Song) {
	key := s.Source + ":" + s.ID
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.items[key]; ok {
		el.Value = s
		c.ll.MoveToFront(el)
		return
	}
	c.items[key] = c.ll.PushFront(s)
	for c.ll.Len() > c.max {
		last := c.ll.Back()
		c.ll.Remove(last)
		old := last.Value.(model.Song)
		delete(c.items, old.Source+":"+old.ID)
	}
}

func (c *songCache) get(source, id string) (*model.Song, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[source+":"+id]
	if !ok {
		return nil, false
	}
	c.ll.MoveToFront(el)
	s := el.Value.(model.Song)
	return &s, true
}

func toTrack(s *model.Song) room.TrackRef {
	return room.TrackRef{
		Source:     s.Source,
		ID:         s.ID,
		Title:      s.Name,
		Artists:    splitArtists(s.Artist),
		Album:      s.Album,
		DurationMs: int64(s.Duration) * 1000,
		CoverURL:   s.Cover,
	}
}

func splitArtists(s string) []string {
	parts := strings.FieldsFunc(s, func(r rune) bool { return r == '/' || r == '、' || r == ',' || r == '，' })
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	if len(out) == 0 && strings.TrimSpace(s) != "" {
		out = append(out, strings.TrimSpace(s))
	}
	return out
}
