package identity

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadCreatesAndReloadsSameKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "key.pem")
	a, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("key file mode %v, want 0600", st.Mode().Perm())
	}
	b, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if a.ID() != b.ID() {
		t.Fatalf("reload changed id: %s vs %s", a.ID(), b.ID())
	}
	if len(a.ID()) != 29 { // 24 位 + 5 个横线
		t.Fatalf("unexpected id %q", a.ID())
	}
}

func TestProofVerifiesOnlyForSameKeyAndNonce(t *testing.T) {
	dir := t.TempDir()
	a, _ := Load(filepath.Join(dir, "a.pem"))
	b, _ := Load(filepath.Join(dir, "b.pem"))
	nonce := []byte("0123456789abcdef")
	sig, err := a.Prove(nonce)
	if err != nil {
		t.Fatal(err)
	}
	if !Verify(a.PublicKey(), nonce, sig) {
		t.Fatal("valid proof rejected")
	}
	if Verify(b.PublicKey(), nonce, sig) {
		t.Fatal("proof accepted for another server's key")
	}
	if Verify(a.PublicKey(), []byte("another nonce!!!"), sig) {
		t.Fatal("proof accepted for a different nonce")
	}
}

func TestCorruptKeyFileIsAnError(t *testing.T) {
	path := filepath.Join(t.TempDir(), "key.pem")
	os.WriteFile(path, []byte("garbage"), 0o600)
	if _, err := Load(path); err == nil {
		t.Fatal("expected error for corrupt key file")
	}
}
