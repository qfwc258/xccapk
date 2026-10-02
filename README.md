# xccapk

`main` 只放 TV 源配置和 GitHub Actions。

- App 源码与云编译：`xcctvhelper` 分支
- 源配置：`tv/`（默认同步 `vod.json` 及点播依赖，不含大体积直播清单）
- 每日同步：`.github/workflows/sync-vod.yml`
- APK 构建：在 `xcctvhelper` 推送时触发 `.github/workflows/build.yml`

播放器源地址示例：

```text
https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json
```
