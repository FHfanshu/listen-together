# 一起听 · 服务端

本服务端基于 [go-music-dl](https://github.com/guohuiyuan/go-music-dl)，保留其 [AGPL-3.0 许可证](LICENSE)及[项目说明和致谢](UPSTREAM.md)。一起听的房间、同步和客户端接口位于 `internal/lt/`。

使用 Go 1.25.1 或更新版本：

```bash
go test ./internal/lt/...
go build -o ./bin/music-dl ./cmd/music-dl
./bin/music-dl web --port 8080 --no-browser
```

启动后，在运行服务端的机器上打开 [管理页](http://localhost:8080/music/lt/admin)创建邀请码。在 Android 客户端填写手机可访问的服务端地址和邀请码。如果需要公网接入，使用 HTTPS，并保持后台的默认本机访问限制；客户端接口位于 `/api/v1/`。

配置、数据库及服务器身份密钥由本地服务生成或保存，请勿提交到 Git。
