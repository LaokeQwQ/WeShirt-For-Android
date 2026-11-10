# 开发说明

Android 10 及以上，Kotlin + Jetpack Compose Material 3，Filament 原生 3D。所有页面由原生控件绘制；登录拉起钉钉客户端，未安装时给出提示。

## 构建

需要 JDK 17 或 21、Android SDK 36。项目包含 Gradle Wrapper 和发行包 SHA-256 校验。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本地 `local.properties` 配置 `sdk.dir`。服务端地址可以在应用中填写 HTTPS 根地址，也可以通过私有 `WESHIRT_API_BASE_URL` 环境变量注入。公开源码不包含学校、正式域名、账号或凭据。

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
./gradlew :app:assembleRelease
```

需要保留同一签名才能覆盖升级。APK 不上传公共制品站；私有分发时同时提供版本号和 SHA-256。

## 数据与性能

- 安装 ID 保存在本地；没有临时昵称账号，游客只读，登录后复用服务端账号。
- 令牌由 Android Keystore 加密。刷新和退出串行处理；每次请求固定服务地址，切服务后旧响应不得恢复会话。
- 写操作禁用重复点击，服务端继续校验权限和幂等键。网络失败包含状态码和事件 ID。
- 看板按住宿楼查询，前台间隔刷新；后台停止刷新、3D 和不必要的渲染。服务器公开缓存遵循 HTTP 缓存头。
- 3D 以实际 GPU 帧时间和持续压力窗口判断降级，主动限帧不计作掉帧。温度、省电、屏幕刷新率会限制性能目标；降级提示显示三秒。
- 倒计时是预计时间。到零不会把设备自动改为空闲，也不会伪造“已完成”服务器通知。
- 公告以原生 Markdown 渲染；聊天图片和视频沿用频道鉴权。附件不使用公开存储链接。

登录、推送、服务端契约分别见 [LOGIN.md](LOGIN.md)、[PUSH.md](PUSH.md)、[API.md](API.md)。
