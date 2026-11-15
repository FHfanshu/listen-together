package web

import (
	"net"
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
)

// 一起听把这个服务开到公网（Cloudflare 隧道）或局域网给朋友的 App 用，
// 但网页端和管理后台本来是给本机用的，能改下载目录、往磁盘写文件。
// 所以除了 App 接口（/api/v1）和健康检查，其余页面只允许本机直接访问。
//
// 注意隧道转进来的请求来源地址也是 127.0.0.1，必须再看有没有代理头：
// Cloudflare 一定会加 Cf-Connecting-Ip，客户端没法去掉。
// 需要从别的设备管理时，设置环境变量 LT_ALLOW_REMOTE_ADMIN=1 关掉这个限制。
func localOnlyMiddleware() gin.HandlerFunc {
	if os.Getenv("LT_ALLOW_REMOTE_ADMIN") == "1" {
		return func(c *gin.Context) { c.Next() }
	}
	return func(c *gin.Context) {
		path := c.Request.URL.Path
		if strings.HasPrefix(path, "/api/v1/") || path == "/api/v1" || path == RoutePrefix+"/healthz" {
			c.Next()
			return
		}
		if !isDirectLocal(c.Request) {
			c.String(http.StatusForbidden, "管理页只能在服务器本机打开（App 不受影响）。")
			c.Abort()
			return
		}
		c.Next()
	}
}

var proxyHeaders = []string{"Cf-Connecting-Ip", "Cf-Ray", "X-Forwarded-For", "X-Real-Ip", "Forwarded"}

func isDirectLocal(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return false
	}
	for _, h := range proxyHeaders {
		if r.Header.Get(h) != "" {
			return false
		}
	}
	return true
}
