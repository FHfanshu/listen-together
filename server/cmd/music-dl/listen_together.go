package main

import (
	"github.com/guohuiyuan/go-music-dl/internal/lt/appapi"
	"github.com/guohuiyuan/go-music-dl/internal/web"
)

func init() {
	web.ExtraRoutes = append(web.ExtraRoutes, appapi.Register)
	web.HomePath = "/lt/admin"
}
