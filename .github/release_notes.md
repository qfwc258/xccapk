## XCCTV TVHelper v${VERSION}

### 文件
- APK: ${APK_NAME}
- 大小: ${APK_SIZE}
- SHA-256: ${SHA256}
- 签名: release.keystore (alias: xcctv)

### 功能
- TV / 手机双端自动识别 + 遥控器焦点链
- 4 线程并行下载（Semaphore + Mutex）
- 嵌套路径循环扫描（drpy2.min.js -> ./uri.min.js 自动发现）
- 固定保存到 /sdcard/xcctv（需 MANAGE_EXTERNAL_STORAGE 权限）
- TV 焦点颜色高亮（紫色填充 + 青色发光边框 + 1.12x 放大）
- 下载状态横幅（下载中 / 完成 / 失败 三态）

### 安装
1. 安装 APK
2. 首次打开点 "授权" 授予所有文件访问权限
3. TV 端默认源已预填，手机端可自定义
4. 点 "开始下载" -> 文件保存到 /sdcard/xcctv
5. TVBox/FongMi 添加源: file://xcctv/vod.json

### 更新日志
- 嵌套路径循环扫描
- /sdcard 直接写入 + 权限申请
- TV 焦点三重兜底
