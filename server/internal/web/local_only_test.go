package web

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestLocalOnlyMiddleware(t *testing.T) {
	t.Setenv("LT_ALLOW_REMOTE_ADMIN", "")
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(localOnlyMiddleware())
	r.GET("/*any", func(c *gin.Context) { c.String(200, "ok") })

	cases := []struct {
		name, path, remote string
		header             map[string]string
		want               int
	}{
		{"本机打开管理页", RoutePrefix + "/lt/admin", "127.0.0.1:5000", nil, 200},
		{"本机 IPv6", RoutePrefix + "/", "[::1]:5000", nil, 200},
		{"局域网打开管理页", RoutePrefix + "/lt/admin", "10.0.0.8:5000", nil, 403},
		{"隧道转进来的管理页", RoutePrefix + "/lt/admin", "127.0.0.1:5000", map[string]string{"Cf-Connecting-Ip": "1.2.3.4"}, 403},
		{"其他代理转进来", RoutePrefix + "/settings", "127.0.0.1:5000", map[string]string{"X-Forwarded-For": "1.2.3.4"}, 403},
		{"隧道访问 App 接口", "/api/v1/rooms", "127.0.0.1:5000", map[string]string{"Cf-Connecting-Ip": "1.2.3.4"}, 200},
		{"局域网访问 App 接口", "/api/v1/ws", "10.0.0.8:5000", nil, 200},
		{"健康检查", RoutePrefix + "/healthz", "10.0.0.8:5000", nil, 200},
		{"根路径跳转也算网页端", "/", "10.0.0.8:5000", nil, 403},
	}
	for _, tc := range cases {
		req := httptest.NewRequest(http.MethodGet, tc.path, nil)
		req.RemoteAddr = tc.remote
		for k, v := range tc.header {
			req.Header.Set(k, v)
		}
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != tc.want {
			t.Errorf("%s: got %d want %d", tc.name, w.Code, tc.want)
		}
	}

	t.Setenv("LT_ALLOW_REMOTE_ADMIN", "1")
	r2 := gin.New()
	r2.Use(localOnlyMiddleware())
	r2.GET("/*any", func(c *gin.Context) { c.String(200, "ok") })
	req := httptest.NewRequest(http.MethodGet, RoutePrefix+"/lt/admin", nil)
	req.RemoteAddr = "10.0.0.8:5000"
	w := httptest.NewRecorder()
	r2.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("LT_ALLOW_REMOTE_ADMIN=1 should allow remote, got %d", w.Code)
	}
}
