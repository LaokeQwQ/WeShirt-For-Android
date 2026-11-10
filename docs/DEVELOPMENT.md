# 开发说明

Android 10 及以上，Kotlin + Jetpack Compose Material 3，Filament 原生 3D。所有页面由原生控件绘制；登录拉起钉钉客户端，未安装时给出提示。

## 构建

需要 JDK 17 或 21、Android SDK 36。项目包含 Gradle Wrapper 和发行包 SHA-256 校验。

```sh
./gradlew -p buildSrc test --no-configuration-cache
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-configuration-cache
```

本地 `local.properties` 配置 `sdk.dir`。服务端地址可以在应用中填写 HTTPS 根地址，也可以通过私有 `WESHIRT_API_BASE_URL` 环境变量注入。公开源码不包含学校、正式域名、账号或凭据。

`local.properties` 使用 Java Properties 格式；Windows 路径请写为 `C:/Android/sdk` 这样的正斜杠路径，避免反斜杠被当作转义符。环境变量优先于同名本地属性。客户端推送变量及可选华为配置见 [PUSH.md](PUSH.md)，服务端凭据和 CloudBase 原子存储见 [API.md](API.md)。

Android 仓库保留原有 `main` 分支，开发提交先进入 `dev`，通过 CI 和审核后晋升 `main`。后端仓库继续使用 `dev → master`，生产由 `master` 部署。

## 自签名分发

正式包名 `com.laoke.weshirt`；调试包名额外包含 `.debug`。钉钉及厂商平台须登记正式包名与实际签名指纹，调试包需要单独登记。签名私钥始终保存在仓库之外。

发布构建读取以下本地配置或同名环境变量：

| 配置 | 用途 |
| --- | --- |
| `WESHIRT_KEYSTORE` | 私有 keystore 路径 |
| `WESHIRT_STORE_PASSWORD` | keystore 密码 |
| `WESHIRT_KEY_ALIAS` | 默认为 `weshirt` |
| `WESHIRT_KEY_PASSWORD` | 签名密码 |

```sh
./gradlew :app:assembleRelease --no-configuration-cache
```

需要保留同一签名才能覆盖升级。APK 不上传公共制品站；私有分发时同时提供版本号和 SHA-256。

仓库不附带 keystore。没有配置签名时，`assembleRelease` 只会生成未签名 APK；必须使用部署方保管的自签名证书签名后才能安装、覆盖升级和登记厂商应用。正式授权及投递验收使用 `com.laoke.weshirt` 的实际分发包与签名。

## 当前接入范围

原生页面、3D、登记、排队、频道媒体、朋友代取、本地通知与桌面小组件均在本仓库。频道目前使用前台约 45 秒的 HTTP 刷新，并在操作、页面恢复和推送到达时更新；尚未接入原生 WebSocket。后端的原生握手票据扩展见 [API.md](API.md)。

FCM 和 U-Push 已有实际 SDK 回调与后端发送适配器；小米和华为经 U-Push 聚合。登记应用、云端配置、签名和各品牌真机验收是正式投递的前提。OPPO、vivo、荣耀等单独的厂商 SDK、厂商私有灵动岛以及 iOS/TestFlight 不属于当前 Android 交付；Android 16 的标准 Live Updates 由系统决定是否展示。

## 数据与性能

- 安装 ID 保存在本地；没有临时昵称账号，游客只读，登录后复用服务端账号。
- 令牌由 Android Keystore 加密。刷新和退出串行处理；每次请求固定服务地址，切服务后旧响应不得恢复会话。
- 写操作禁用重复点击，服务端继续校验权限和幂等键。网络失败包含状态码和事件 ID。
- 看板按住宿楼查询，前台间隔刷新；后台停止刷新、3D 和不必要的渲染。服务器公开缓存遵循 HTTP 缓存头。
- 楼栋、资料、收藏设置和公告缓存五分钟；手动刷新立即重新查询。正式包仅携带 ARM 原生库，调试包额外支持 x86 模拟器。
- 3D 以实际 GPU 帧时间和持续压力窗口判断降级，主动限帧不计作掉帧。温度、省电、屏幕刷新率会限制性能目标；降级提示显示三秒。
- 倒计时是预计时间。到零不会把设备自动改为空闲，也不会伪造“已完成”服务器通知。
- 公告以原生 Markdown 渲染；聊天图片和视频沿用频道鉴权。附件不使用公开存储链接。

登录、推送、服务端契约分别见 [LOGIN.md](LOGIN.md)、[PUSH.md](PUSH.md)、[API.md](API.md)。
