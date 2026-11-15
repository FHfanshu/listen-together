package web

import "github.com/gin-gonic/gin"

// ExtraRoutes 让 fork 出来的功能在不改动上游路由代码的前提下挂接口。
// configAPI 是需要网页登录的分组（前缀 RoutePrefix）。
var ExtraRoutes []func(r *gin.Engine, configAPI *gin.RouterGroup)

// HomePath 非空时（相对 RoutePrefix，如 "/lt/admin"），站点根路径和原首页都跳到这里，
// 不再展示 go-music-dl 自带的下载首页。
var HomePath string

func homeRedirect(c *gin.Context) bool {
	if HomePath == "" {
		return false
	}
	c.Redirect(302, RoutePrefix+HomePath)
	return true
}
