// Package identity 管理服务器自己的身份密钥。
//
// 服务器第一次启动时生成一对 ECDSA P-256 密钥存在 data/ 里，公钥指纹就是服务器 ID。
// App 第一次加入时记下公钥；以后无论地址怎么变，都先让服务器对 App 给的随机数签名，
// 签名对得上才把自己的 token 发过去。ID 由公钥算出，不能自定义；冒充者没有私钥就签不出来。
package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base32"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// ProofContext 拼在随机数前面再签名，避免这把密钥签出的东西被挪作他用。App 端必须一致。
const ProofContext = "listen-together server proof v1\n"

type Identity struct {
	key    *ecdsa.PrivateKey
	pubDER []byte
}

// Load 读取密钥文件，不存在就生成一个（权限 0600）。文件损坏时报错而不是悄悄换新：
// 换了密钥等于换了服务器身份，所有人都得重新加入，必须让开服的人知道。
func Load(path string) (*Identity, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return create(path)
	}
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("%s 不是有效的 PEM 文件", path)
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("解析 %s: %w", path, err)
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, fmt.Errorf("%s 不是 P-256 密钥", path)
	}
	return fromKey(ec)
}

func create(path string) (*Identity, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	// O_EXCL：两个进程同时首次启动时不会互相覆盖
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			return Load(path)
		}
		return nil, err
	}
	defer f.Close()
	if err := pem.Encode(f, &pem.Block{Type: "PRIVATE KEY", Bytes: der}); err != nil {
		return nil, err
	}
	return fromKey(k)
}

func fromKey(k *ecdsa.PrivateKey) (*Identity, error) {
	pub, err := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if err != nil {
		return nil, err
	}
	return &Identity{key: k, pubDER: pub}, nil
}

// PublicKey 是 DER 编码的 SubjectPublicKeyInfo。
func (i *Identity) PublicKey() []byte { return i.pubDER }

// ID 是公钥 SHA-256 的前 15 字节，base32 小写，每 4 位一组，方便人眼核对。
func (i *Identity) ID() string { return Fingerprint(i.pubDER) }

func Fingerprint(pubDER []byte) string {
	sum := sha256.Sum256(pubDER)
	s := strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(sum[:15]))
	var b strings.Builder
	for j := 0; j < len(s); j += 4 {
		if j > 0 {
			b.WriteByte('-')
		}
		b.WriteString(s[j:min(j+4, len(s))])
	}
	return b.String()
}

// Prove 对 App 给的随机数签名（ASN.1 DER 格式的 ECDSA，摘要 SHA-256）。
func (i *Identity) Prove(nonce []byte) ([]byte, error) {
	sum := sha256.Sum256(append([]byte(ProofContext), nonce...))
	return ecdsa.SignASN1(rand.Reader, i.key, sum[:])
}

// Verify 给测试和其他 Go 客户端用。
func Verify(pubDER, nonce, sig []byte) bool {
	k, err := x509.ParsePKIXPublicKey(pubDER)
	if err != nil {
		return false
	}
	ec, ok := k.(*ecdsa.PublicKey)
	if !ok {
		return false
	}
	sum := sha256.Sum256(append([]byte(ProofContext), nonce...))
	return ecdsa.VerifyASN1(ec, sum[:], sig)
}
