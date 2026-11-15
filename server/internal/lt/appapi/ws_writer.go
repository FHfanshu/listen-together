package appapi

import (
	"bufio"
	"net"
	"net/http"

	"github.com/gin-gonic/gin"
)

// wsWriter 让 coder/websocket 能在 gin 1.11 上完成握手。
//
// coder/websocket 发现 gin 的 writer 会先调 WriteHeaderNow，而 gin 1.11 的 Hijack
// 一旦看到响应已写出就拒绝。这里把 101 状态直接交给底层 net/http writer，
// 再通过 gin 的 Hijack 接管连接，gin 因此知道连接已被接管，之后不会再写响应头。
type wsWriter struct {
	gin gin.ResponseWriter
}

func (w wsWriter) Header() http.Header         { return w.gin.Header() }
func (w wsWriter) Write(b []byte) (int, error) { return w.gin.Write(b) }
func (w wsWriter) WriteHeader(code int) {
	if code == http.StatusSwitchingProtocols {
		w.gin.(interface{ Unwrap() http.ResponseWriter }).Unwrap().WriteHeader(code)
		return
	}
	w.gin.WriteHeader(code)
}
func (w wsWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) { return w.gin.Hijack() }
