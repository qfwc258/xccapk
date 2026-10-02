# XCCTV 助手

Android TV / 手机端源文件下载助手。解析 TVBox / FongMi 配置里的相对路径，把 `vod.json` 及依赖的 jar、js、json 下载到 `/sdcard/xcctv`，供播放器以 `file://xcctv/` 协议读取。

## 工程结构

```
app/                 Android 应用
  src/main/java/com/xcctv/tvhelper/
    MainActivity.kt            主界面
    XcctvSourceDownloader.kt   源配置解析与并行下载
    XcctvProvider.kt           file://xcctv/ ContentProvider
    BootReceiver.kt            开机拉起
    AppConstants.kt            常量
    StoragePaths.kt            本地目录
signing/             Release 签名
tv/                  同步自 qist/tvbox 的源配置（构建 APK 时不参与编译）
.github/workflows/   APK 构建与源同步
```

## 功能

- TV / 手机自动识别，横屏 TV 用左右分栏布局
- 遥控器焦点放大，按钮与输入框使用 TV 焦点选择器
- 4 线程并行下载，JSON / JS / PY 嵌套相对路径循环扫描
- 固定写入 `/sdcard/xcctv`（需全部文件访问权限）
- `file://xcctv/vod.json` 给 TVBox / FongMi 使用

## 使用

1. 安装 APK
2. 首次打开点「授权」，授予所有文件访问权限
3. 源地址已预填，可改成自定义直链
4. 点「开始下载」，文件保存到 `/sdcard/xcctv`
5. 播放器添加源：`file://xcctv/vod.json`

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release（读取 gradle.properties 或 -P 参数中的签名信息）
./gradlew assembleRelease
```

输出 APK：`app/build/outputs/apk/<buildType>/XCCTV-TVHelper-<version>-<buildType>.apk`

最低 SDK 21，目标 SDK 34。JDK 17 + Gradle 8.5。
