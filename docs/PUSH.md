# Android 通知、进度卡片与小组件

## 投递通道

每个 installation 仅绑定一个主通道。`PushRouter` 在国内模式选择友盟 U-Push 聚合，在 Google 模式选择 FCM，自动模式根据可用的 Google Play 服务与已配置通道选择。不会同时给同一安装实例登记 FCM 和 U-Push。小米、华为属于友盟下的厂商离线通道，不额外绑定成第二主通道。

FCM 使用真正的 `FirebaseMessagingService.onNewToken/onMessageReceived`；友盟使用 `UPushRegisterCallback/UPushMessageHandler`。SDK 不可用或配置缺失会显示 `NO_CONFIGURED_PROVIDER` 等状态，收到 token 后仍须后端登记成功才能显示 `REGISTERED`。没有厂商凭据时不调用对应注册接口，不展示“已送达”。

客户端配置通过 `PushConfiguration` 注入，默认没有凭据、没有同意状态。`initialize` 只能在读取隐私选择后调用；没有同意时不初始化第三方 SDK。FCM 的默认 Provider 自动初始化及 Messaging auto-init 在 Manifest 中关闭，配置齐全后才手动初始化。友盟关闭应用列表与电话标识采集、SDK日志、拉活、额外心跳、前台常驻服务。业务不启动任何保活服务。

合并清单移除了广告 ID、Freeme 标识权限、小米日志上传服务/Provider，以及 HMS AAID/AGConnect 自动初始化 Provider。推送入站组件默认禁用，仅在已登录、明确同意、系统通知允许且对应通道已配置后启用。HMS 使用显式 `AGConnectInstance.initialize`，只有本地配置含有效 app_id 才注册；未配置保持停用。应用进程由系统推送唤醒时，只恢复此前同意的 SDK 配置，不另建 API 客户端或发起会话刷新。

小米需申请包名对应 App ID/App Key；华为需登记包名、签名证书并配置匹配的 HMS 应用信息。友盟服务端发送 Secret 不得写入 APK。友盟客户端的 message secret、公开 App Key 与服务端发送凭据用途不同，仍应从本地私有配置生成，避免混入仓库。

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
  "serverConfirmed": "true"
}
```

kind 支持 `QUEUE_THREE_MINUTES`、`ESTIMATED_END`、`TASK_FINISHED`、`CHAT_MENTION`、`ANNOUNCEMENT`、`PICKUP_REQUEST`。确认完成事件必须带 `serverConfirmed=true`；预计时间到不会被当成设备已结束。无效字段拒绝显示，点击仅进入本机原生页面，不执行推送给出的任意 URL。

相同 eventId 只有更大 revision 能再次更新。通知投递账本保留最近 256 项，应用重启后仍去重。后端应使用稳定 eventId 并设置短 TTL，禁止跨用户复用或长期重放。FCM 不要发送自动展示的 notification payload，否则会绕开应用去重与退出状态判断。友盟的自定义处理器同样统一到该路径；厂商离线通知的控制台模板需配置原生应用打开动作并在真机验证，不能设置打开网页。

## 本地提醒与进度

Android 13+ 在用户明确开启通知时申请 `POST_NOTIFICATIONS`。拒绝权限不影响查询与登记功能。用户还可在系统设置中独立禁用消息频道。

`LaundryReminders.schedule` 按当前剩余时间安排结束前 3 分钟与预计结束提醒。任务修订会替换旧工作，取消、取衣、服务端确认结束或退出登录会撤销工作。使用 WorkManager，不申请精确闹钟或后台保活；省电与系统调度可能延后本地提醒，文案明确为预计。服务端设备确认后的消息才称“已结束”。

`LaundryNotifications.updateProgress` 提供原生进度通知及系统 Chronometer 倒计时，没有逐秒联网轮询。Android 16/API 36+ 会在用户主动跟踪本次任务、任务仍在进行且 `canPostPromotedNotifications()` 返回允许时请求 Live Update。是否实际晋升由系统决定。用户划掉后不会持续重弹同一任务。

“灵动岛”不是 Android 统一接口。标准 Live Update 不能保证在每家系统上显示专有岛形界面；小米、华为、OPPO、vivo、荣耀等私有增强通常还需要厂商审核、白名单或额外 SDK，当前不宣称已接入这些私有界面。未获能力时回退普通原生进度通知，通知仍可用。HarmonyOS NEXT 不等同于 Android HMS，需另建原生客户端。

## 小组件

原生 `AppWidgetProvider` 只保存当前楼栋的数量摘要、本人任务机器名、预计时间和更新时间。没有账号、手机号、备注、聊天、头像或推送 token。点按通过 PendingIntent 打开原生主 Activity。快照超过 10 分钟提示打开刷新，不在后台高频联网。退出后即清空桌面内容。

## 设备验收

单元测试覆盖单通道路由、无配置/无同意、修订去重和完成状态区分。发布前需使用登记后的真实应用分别验证 Google Play、无 GMS 小米、华为 HMS：首次同意、拒绝通知、系统禁用频道、切换账号、断网退出、杀进程后推送、token 轮换、取消排队、省电模式提醒延迟、重复与乱序消息、进度卡片划掉、桌面小组件和 API 36 的允许/禁用 Live Updates。没有配置或没有测试设备的项目应保留未验收状态。

参考：[Firebase Android 消息接收](https://firebase.google.com/docs/cloud-messaging/android/receive)、[友盟官方示例](https://github.com/umeng/MultiFunctionAndroidDemo)、[Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)。
