# XCCTV 助手

Android TV / 手机端源文件下载助手。解析 TVBox / FongMi 配置里的相对路径，把 `vod.json` 及依赖的 jar、js、json 增量下载到 `/sdcard/xcctv`（不可写时回退到应用外部目录），供播放器以 `file://xcctv/` 协议读取。

## 工程结构

```
app/                 Android 应用
  src/main/java/com/xcctv/tvhelper/
    MainActivity.kt            主界面
    XcctvSourceDownloader.kt   源配置解析、增量下载、失败重试、可取消
    XcctvProvider.kt           file://xcctv/ ContentProvider
    BootReceiver.kt            开机自启（默认关闭）
    AppConstants.kt            常量与源预设
    StoragePaths.kt            本地目录兜底
signing/             Release 签名
tv/                  源配置（构建 APK 时不参与编译）
.github/workflows/   APK 构建与源同步
```

## 功能

- TV / 手机自动识别；TV 用磁贴选源、白环焦点闭环
- 源预设：点播 vod / 合集 jsm / 自定义（可清空，地址单独记忆）
- 4 线程并行下载；本地已存在且 MD5 匹配则跳过
- 单文件失败自动重试 2 次，结束后列出失败清单
- 下载可停止；进度条按完成数 / 总数更新
- 日志最多保留 80 行
- 优先写入 `/sdcard/xcctv`，不可写则用应用外部目录 `xcctv`
- `file://xcctv/vod.json` 给 TVBox / FongMi 使用
- 开机自启开关，默认关闭

## 使用

1. 安装 APK
2. 首次打开可点「授权」，授予所有文件访问权限（不授权则写入应用目录）
3. 选择点播 / 合集，或选「自定义」粘贴直链（可清空）
4. 点「开始下载」，可用「停止」中断
5. 播放器添加源：`file://xcctv/vod.json`

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release（读取 gradle.properties 或 -P 参数中的签名信息）
./gradlew assembleRelease
```

输出 APK：`app/build/outputs/apk/<buildType>/XCCTV-TVHelper-<version>-<buildType>.apk`

最低 SDK 21，目标 SDK 34。JDK 17 + Gradle 8.5。当前版本 1.5。
