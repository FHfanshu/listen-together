// Package members 管理一起听的圈子成员：管理员发邀请码，成员凭邀请码换取长期 token。
package members

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/glebarez/sqlite"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

var (
	ErrInviteInvalid = errors.New("邀请码无效或已过期")
	ErrUnauthorized  = errors.New("token 无效或成员已停用")
	ErrBadNickname   = errors.New("昵称不能为空且不超过 32 个字符")
	ErrBadAvatar     = errors.New("头像格式不正确")
)

// 去掉了 0/O/1/I 等容易看错的字符。
const inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

type Invite struct {
	Code      string     `gorm:"primaryKey;size:16" json:"code"`
	MaxUses   int        `json:"maxUses"` // 0 = 不限次数
	Uses      int        `json:"uses"`
	ExpiresAt *time.Time `json:"expiresAt,omitempty"`
	CreatedAt time.Time  `json:"createdAt"`
}

type Member struct {
	ID       string `gorm:"primaryKey;size:36" json:"userId"`
	Nickname string `gorm:"size:64" json:"nickname"`
	// Avatar 为空表示按 userId 生成的默认头像；"preset:N" 为内置头像；"photo:<版本>" 为上传的照片。
	Avatar    string `gorm:"size:64" json:"avatar"`
	TokenHash string `gorm:"uniqueIndex;size:64" json:"-"`
	Disabled  bool   `json:"disabled"`
	// Admin 是服主指定的管理员：在放映厅任何档位下都能控制进度，也能改房间档位。
	Admin bool `json:"admin"`
	// MutedUntil 之前不能在放映厅发弹幕；nil 表示没被禁言。管理员在放映厅里禁言，服主在管理页禁言或解除。
	MutedUntil *time.Time `json:"mutedUntil,omitempty"`
	CreatedAt  time.Time  `json:"createdAt"`
}

// Setting 是服主在管理页改的服务器级设置，按 key 存字符串。
type Setting struct {
	Key   string `gorm:"primaryKey;size:64"`
	Value string `gorm:"size:256"`
}

type Store struct {
	db  *gorm.DB
	now func() time.Time
}

func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, err
	}
	db, err := gorm.Open(sqlite.Open(path+"?_pragma=busy_timeout(5000)"), &gorm.Config{})
	if err != nil {
		return nil, err
	}
	if err := db.AutoMigrate(&Invite{}, &Member{}, &Setting{}); err != nil {
		return nil, err
	}
	return &Store{db: db, now: time.Now}, nil
}

func (s *Store) Close() error {
	sqlDB, err := s.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

func (s *Store) CreateInvite(maxUses int, ttl time.Duration) (Invite, error) {
	code, err := randomInviteCode(8)
	if err != nil {
		return Invite{}, err
	}
	inv := Invite{Code: code, MaxUses: maxUses, CreatedAt: s.now()}
	if ttl > 0 {
		exp := s.now().Add(ttl)
		inv.ExpiresAt = &exp
	}
	return inv, s.db.Create(&inv).Error
}

func (s *Store) ListInvites() ([]Invite, error) {
	var out []Invite
	return out, s.db.Order("created_at desc").Find(&out).Error
}

// Join 消耗一次邀请码并创建成员，返回明文 token（只在此时出现一次）。
func (s *Store) Join(code, nickname string) (Member, string, error) {
	nickname = strings.TrimSpace(nickname)
	if nickname == "" || len([]rune(nickname)) > 32 {
		return Member{}, "", ErrBadNickname
	}
	code = strings.ToUpper(strings.TrimSpace(code))
	token, err := randomToken()
	if err != nil {
		return Member{}, "", err
	}
	m := Member{ID: uuid.NewString(), Nickname: nickname, TokenHash: hashToken(token), CreatedAt: s.now()}

	err = s.db.Transaction(func(tx *gorm.DB) error {
		var inv Invite
		if err := tx.First(&inv, "code = ?", code).Error; err != nil {
			return ErrInviteInvalid
		}
		if inv.ExpiresAt != nil && s.now().After(*inv.ExpiresAt) {
			return ErrInviteInvalid
		}
		if inv.MaxUses > 0 && inv.Uses >= inv.MaxUses {
			return ErrInviteInvalid
		}
		if err := tx.Model(&inv).Update("uses", inv.Uses+1).Error; err != nil {
			return err
		}
		return tx.Create(&m).Error
	})
	if err != nil {
		return Member{}, "", err
	}
	return m, token, nil
}

func (s *Store) Authenticate(token string) (Member, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return Member{}, ErrUnauthorized
	}
	var m Member
	if err := s.db.First(&m, "token_hash = ?", hashToken(token)).Error; err != nil {
		return Member{}, ErrUnauthorized
	}
	if m.Disabled {
		return Member{}, ErrUnauthorized
	}
	return m, nil
}

func (s *Store) ListMembers() ([]Member, error) {
	var out []Member
	return out, s.db.Order("created_at").Find(&out).Error
}

// UpdateProfile 修改昵称和/或头像，传 nil 表示不改。
func (s *Store) UpdateProfile(id string, nickname, avatar *string) (Member, error) {
	updates := map[string]any{}
	if nickname != nil {
		n := strings.TrimSpace(*nickname)
		if n == "" || utf8.RuneCountInString(n) > 32 {
			return Member{}, ErrBadNickname
		}
		updates["nickname"] = n
	}
	if avatar != nil {
		if !ValidAvatar(*avatar) {
			return Member{}, ErrBadAvatar
		}
		updates["avatar"] = *avatar
	}
	if len(updates) > 0 {
		if err := s.db.Model(&Member{}).Where("id = ?", id).Updates(updates).Error; err != nil {
			return Member{}, err
		}
	}
	var m Member
	return m, s.db.First(&m, "id = ?", id).Error
}

// PresetAvatars 是内置头像的数量，客户端按同样的编号渲染。
const PresetAvatars = 24

func ValidAvatar(a string) bool {
	switch {
	case a == "":
		return true
	case strings.HasPrefix(a, "preset:"):
		n, err := strconv.Atoi(strings.TrimPrefix(a, "preset:"))
		return err == nil && n >= 0 && n < PresetAvatars
	case strings.HasPrefix(a, "photo:"):
		_, err := strconv.ParseInt(strings.TrimPrefix(a, "photo:"), 10, 64)
		return err == nil
	}
	return false
}

func (s *Store) SetDisabled(id string, disabled bool) error {
	res := s.db.Model(&Member{}).Where("id = ?", id).Update("disabled", disabled)
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}

func (s *Store) SetAdmin(id string, admin bool) error {
	res := s.db.Model(&Member{}).Where("id = ?", id).Update("admin", admin)
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}

// SetMute 设置禁言到期时间，nil 表示解除。
func (s *Store) SetMute(id string, until *time.Time) error {
	res := s.db.Model(&Member{}).Where("id = ?", id).Update("muted_until", until)
	if res.Error != nil {
		return res.Error
	}
	if res.RowsAffected == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}

// Mutes 返回还没到期的禁言。
func (s *Store) Mutes() (map[string]time.Time, error) {
	var list []Member
	if err := s.db.Where("muted_until > ?", s.now()).Find(&list).Error; err != nil {
		return nil, err
	}
	out := map[string]time.Time{}
	for _, m := range list {
		out[m.ID] = *m.MutedUntil
	}
	return out, nil
}

// AdminIDs 返回所有管理员（含已停用的，停用的人反正连不上）。
func (s *Store) AdminIDs() ([]string, error) {
	var ids []string
	return ids, s.db.Model(&Member{}).Where("admin = ?", true).Pluck("id", &ids).Error
}

// Setting 读一项设置，没设置过返回 def。
func (s *Store) Setting(key, def string) string {
	// 用 Find 而不是 First：没设置过是常态，First 会在日志里报 record not found。
	var list []Setting
	if err := s.db.Where("key = ?", key).Limit(1).Find(&list).Error; err != nil || len(list) == 0 {
		return def
	}
	return list[0].Value
}

func (s *Store) SetSetting(key, value string) error {
	return s.db.Save(&Setting{Key: key, Value: value}).Error
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func randomToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func randomInviteCode(n int) (string, error) {
	var b strings.Builder
	max := big.NewInt(int64(len(inviteAlphabet)))
	for i := 0; i < n; i++ {
		idx, err := rand.Int(rand.Reader, max)
		if err != nil {
			return "", err
		}
		b.WriteByte(inviteAlphabet[idx.Int64()])
	}
	return b.String(), nil
}
