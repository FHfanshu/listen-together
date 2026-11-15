package room

import (
	"crypto/rand"
	"fmt"
	"math/big"
	"sort"
	"sync"
)

type Manager struct {
	mu    sync.Mutex
	rooms map[string]*Room
	clock Clock
	out   Broadcaster
	warm  func(TrackRef) error
	// Video 是放映厅用的管理员名单和默认档位，建好 Manager 后由接口层设置。
	Video VideoEnv
}

func NewManager(clock Clock, out Broadcaster, warm func(TrackRef) error) *Manager {
	return &Manager{rooms: map[string]*Room{}, clock: clock, out: out, warm: warm}
}

// Create 新建一个空的听歌房间；调用方随后用 Attach 让创建者进房（第一个进房的人即房主）。
func (m *Manager) Create() (*Room, error) { return m.CreateKind(KindMusic) }

// CreateKind 新建指定类型的房间；不认识的类型按听歌房间处理。
func (m *Manager) CreateKind(kind string) (*Room, error) {
	if kind != KindVideo {
		kind = KindMusic
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := 0; i < 20; i++ {
		n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
		if err != nil {
			return nil, err
		}
		code := fmt.Sprintf("%06d", n.Int64())
		if _, taken := m.rooms[code]; taken {
			continue
		}
		var r *Room
		r = newRoom(code, m.clock, m.out, m.warm, func() { m.remove(code, r) })
		r.kind = kind
		r.env = &m.Video
		m.rooms[code] = r
		return r, nil
	}
	return nil, fmt.Errorf("房间号已用尽")
}

func (m *Manager) Get(code string) (*Room, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.rooms[code]
	return r, ok
}

func (m *Manager) List() []Summary {
	m.mu.Lock()
	rooms := make([]*Room, 0, len(m.rooms))
	for _, r := range m.rooms {
		rooms = append(rooms, r)
	}
	m.mu.Unlock()

	out := make([]Summary, 0, len(rooms))
	for _, r := range rooms {
		out = append(out, r.Summary())
	}
	sort.Slice(out, func(i, j int) bool { return out[i].RoomCode < out[j].RoomCode })
	return out
}

// VideoSettingsChanged 在服主改了管理员名单、默认档位或锁定后调用，所有放映厅重新广播控制状态。
func (m *Manager) VideoSettingsChanged() {
	m.mu.Lock()
	rooms := make([]*Room, 0, len(m.rooms))
	for _, r := range m.rooms {
		rooms = append(rooms, r)
	}
	m.mu.Unlock()
	for _, r := range rooms {
		r.videoSettingsChanged()
	}
}

func (m *Manager) remove(code string, r *Room) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.rooms[code] == r {
		delete(m.rooms, code)
	}
}
