# Android 通知、进度卡片与小组件

## 投递通道

客户端仅使用 FCM 和基础 U-Push，每个 installation 仅绑定一个主通道。`PushRouter` 在 Google 模式选择 FCM，在 U-Push 模式选择友盟基础通道，自动模式根据可用的 Google Play 服务与已配置通道选择。不会同时给同一安装实例登记 FCM 和 U-Push，也不按设备所在地判断路由。

FCM 使用 `FirebaseMessagingService.onNewToken/onMessageReceived`；友盟使用 `UPushRegisterCallback/UPushMessageHandler`。SDK 不可用或配置缺失会显示 `NO_CONFIGURED_PROVIDER` 等状态，收到 token 后仍须后端登记成功才能显示 `REGISTERED`。没有发送凭据时不展示“已送达”。基础 U-Push 不等同于设备厂商的离线推送，后台或进程被回收后的实际接收情况仍需真机验证。

客户端配置通过 `PushConfiguration` 注入，默认没有凭据、没有同意状态。`initialize` 只能在读取隐私选择后调用；没有同意时不初始化第三方 SDK。FCM 的默认 Provider 自动初始化及 Messaging auto-init 在 Manifest 中关闭，配置齐全后才手动初始化。友盟关闭应用列表与电话标识采集、SDK 日志、拉活、额外心跳、前台常驻服务，业务不启动保活服务。

合并清单移除广告 ID 和无关标识权限。推送入站组件默认禁用，仅在已登录、明确同意、系统通知允许且对应通道已配置后启用。应用进程由系统推送唤醒时，只恢复此前同意的 SDK 配置，不另建 API 客户端或发起会话刷新。

友盟服务端 Master Secret 不得写入 APK。客户端 Message Secret、AppKey 与服务端发送凭据用途不同，均从本地私有配置生成，避免混入仓库。

## 发行配置

以下内容从本机忽略的 `local.properties` 或同名环境变量读取，环境变量优先。缺任一必需项时对应通道保持不可用；通知同意、登录状态和系统权限仍独立检查。

| 客户端配置 | 用途 |
| --- | --- |
| `WESHIRT_PUSH_FCM_APP_ID` | Firebase Android Application ID |
| `WESHIRT_PUSH_FCM_API_KEY` | 同一 Firebase 项目的客户端 API Key |
| `WESHIRT_PUSH_FCM_PROJECT_ID` | Firebase Project ID |
| `WESHIRT_PUSH_FCM_SENDER_ID` | Firebase Messaging Sender ID |
| `WESHIRT_PUSH_UMENG_APP_KEY` | U-Push Android AppKey |
| `WESHIRT_PUSH_UMENG_MESSAGE_SECRET` | U-Push 客户端 Message Secret |

FCM 使用手动 `FirebaseOptions`，无需提交 `google-services.json`。服务端还需配置同一项目的发送服务账号；U-Push 服务端需要 Master Secret，不能使用 Message Secret 代替。具体变量和发送条件见 [API.md](API.md)。

## 与后端连接

实现 `PushRegistrationSink.register/unregister`，使用已有应用会话调用后端。登记采用 installationId 幂等替换旧 provider/token，token 轮换会重新登记。客户端按顺序执行登记/注销，避免注销覆盖新 token。登录成功调用 `PushRuntime.onSessionAvailable`；退出前调用 suspend `PushRuntime.logout`。服务端退出接口也必须撤销 installation 绑定，处理断网退出时的旧 token；客户端随后不再展示该会话的通知，并清理本地提醒、进度卡片、小组件快照和登录中间数据。

后台登记失败状态为 `REGISTRATION_PENDING`，后续登录恢复或重新 initialize 可重试。服务器应根据投递回执清理失效 token。不要把 token、聊天正文、登录凭证加入匿名遥测。

仅发送数据消息。消息字段均为字符串：

```json
{
  "eventId": "task:example:finished",
  "revision": "2",
  "kind": "TASK_FINISHED",
  "title": "洗衣已结束",
  "body": "设备已确认结束，请及时取衣。",
  "taskId": "example",
  "finishAt": "2026-01-01T12:30:00.000Z",
  "serverConfirmed": "true"
}
```

kind 支持 `QUEUE_THREE_MINUTES`、`ESTIMATED_END`、`TASK_FINISHED`、`CHAT_MENTION`、`ANNOUNCEMENT`、`PICKUP_REQUEST`。确认完成事件必须带 `serverConfirmed=true`；预计时间到不会被当成设备已结束。无效字段拒绝显示，点击仅进入本机原生页面，不执行推送给出的任意 URL。

相同 eventId 只有更大 revision 能再次更新。通知投递账本保留最近 256 项，应用重启后仍去重。后端应使用稳定 eventId 并设置短 TTL，禁止跨用户复用或长期重放。FCM 不要发送自动展示的 notification payload，否则会绕开应用去重与退出状态判断。友盟的自定义处理器同样统一到该路径，点击只进入原生页面。当前不配置离线厂商通知转换。

## 本地提醒与进度

Android 13+ 在用户明确开启通知时申请 `POST_NOTIFICATIONS`。拒绝权限不影响查询与登记功能。用户还可在系统设置中独立禁用消息频道。

`LaundryReminders.schedule` 按当前剩余时间安排结束前 3 分钟与预计结束提醒。任务修订会替换旧工作，取消、取衣、服务端确认结束或退出登录会撤销工作。使用 WorkManager，不申请精确闹钟或后台保活；省电与系统调度可能延后本地提醒，文案明确为预计。服务端设备确认后的消息才称“已结束”。

`LaundryNotifications.updateProgress` 提供原生进度通知及系统 Chronometer 倒计时，没有逐秒联网轮询。Android 16/API 36+ 会在用户主动跟踪本次任务、任务仍在进行且 `canPostPromotedNotifications()` 返回允许时请求 Live Update。是否实际晋升由系统决定。用户划掉后不会持续重弹同一任务。

“灵动岛”不是 Android 统一接口。标准 Live Update 不能保证在每家系统上显示专有岛形界面，当前未接入设备厂商私有增强。未获能力时使用普通原生进度通知。HarmonyOS NEXT 需独立原生客户端。

## 小组件

原生 `AppWidgetProvider` 只保存当前楼栋的数量摘要、本人任务机器名、预计时间和更新时间。没有账号、手机号、备注、聊天、头像或推送 token。点按通过 PendingIntent 打开原生主 Activity。快照超过 10 分钟提示打开刷新，不在后台高频联网。退出后即清空桌面内容。

## 设备验收

UMDID 1.1.0 的自定义 TLS 实现由构建插件替换为系统证书链校验、证书有效期检查和当前主机名校验。变换仅匹配四个已审查类的十九个方法；坐标、原始 JAR SHA-256 或方法结构变化都会中止构建。没有关闭 lint、忽略证书异常或添加信任所有证书的降级。升级该依赖前须重新审查并通过证书与 APK 内实际 SDK 的测试；不能直接改掉指纹校验。构建必须禁用 configuration cache，以确保每次都校验原始 SDK。

本地计时与 FCM/U-Push 按任务、提前/结束阶段和预计结束时间合并；设备确认结束会替换预计结束卡片，迟到的提前提醒会被丢弃。推送仍须用正式登记的应用和对应真机验收；编译通过不代表真实投递已验证。

单元测试覆盖单通道路由、无配置/无同意、修订去重和完成状态区分。发布前需使用登记后的真实应用分别验证带 Google Play 服务的 FCM 设备和基础 U-Push 设备：首次同意、拒绝通知、系统禁用频道、切换账号、断网退出、杀进程后推送、token 轮换、取消排队、省电模式提醒延迟、重复与乱序消息、进度卡片划掉、桌面小组件和 API 36 的允许/禁用 Live Updates。没有配置或没有测试设备的项目应保留未验收状态。

参考：[Firebase Android 消息接收](https://firebase.google.com/docs/cloud-messaging/android/receive)、[友盟官方示例](https://github.com/umeng/MultiFunctionAndroidDemo)、[Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)。
