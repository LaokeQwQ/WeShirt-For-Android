# Android API

Android 包名为 `com.laoke.weshirt`。界面、3D、通知和小组件均由原生代码实现。API 基址由发行配置提供，只允许 HTTPS；私有地址、密钥与签名文件不进入仓库。

## 通用约定

请求和响应使用 JSON，用户请求使用 `Authorization: Bearer <accessToken>`。公开配置和登录挑战不带用户凭据。所有失败响应均提供 HTTP 状态码及可追踪事件编号：

```json
{"error":{"code":"invalid_refresh_token","message":"登录状态已过期，请重新登录","status":401,"eventId":"evt_example"}}
```

`X-Event-ID` 与响应中的事件编号一致。客户端只展示安全的中文说明和事件编号，不把授权码、用户令牌、手机号或推送令牌写入日志。业务写入可使用 `Idempotency-Key`；移动登录、刷新、退出和设备登记由原子会话及设备版本校验负责，不经过通用响应缓存。

## 能力发现

`GET /api/mobile/config`

```json
{
  "protocolVersion":1,
  "packageName":"com.laoke.weshirt",
  "auth":{"enabled":false,"provider":"dingtalk-native","authCodeType":"dingtalk-ddopenauth","reason":"mobile_auth_not_configured","accessTokenTtlSeconds":900,"refreshTokenTtlSeconds":2592000},
  "push":{"registrationEnabled":false,"providers":[]},
  "realtime":{"transport":"http","minRefreshIntervalSeconds":30},
  "widget":{"enabled":true}
}
```

`enabled=false` 表示管理员尚未配置原生应用凭据或原子会话存储。客户端应说明接入尚未完成，不生成临时账号或以网页授权代替原生授权。FCM 与 U-Push 也通过能力发现控制；登记令牌成功不能被当作真实消息投递成功。

## 钉钉原生登录

采用新 `ddopenauth` SDK 的 `AuthLoginParam`、`scope=openid`，拉起钉钉 App 授权。它的统一登录授权码由服务端通过 `oauth2/userAccessToken` 兑换；旧 `ddsharesdk` 的 SNS 临时码属于另一种协议，不能混用。服务端使用独立的移动应用 Client ID/Secret，不回退使用网页凭据。参考[钉钉 Android 登录文档](https://open.dingtalk.com/document/orgapp/android-platform-application-authorization-login-access)。

1. 安装时生成随机 `installationId` 并保存在系统保护存储；长度 16–80，字符为英文字母、数字、`_`、`-`。
2. 每次登录生成独立 `state`（16–80 字符）及 PKCE `codeVerifier`（43–128 个 RFC 7636 允许字符）。计算 `codeChallenge=BASE64URL(SHA256(codeVerifier))`，不含填充符号。
3. `POST /api/mobile/auth/challenge`，请求 `{installationId,state,codeChallenge,platform:"android",packageName:"com.laoke.weshirt"}`。
4. 服务端返回 `{challengeId,state,expiresIn:180,provider:{clientId,scope:"openid",packageName,redirectUri}}`。客户端使用返回的应用信息拉起 SDK，并严格比对回调 `state`。
5. `POST /api/mobile/auth/exchange`，请求 `{challengeId,state,codeVerifier,authCode}`。挑战只能使用一次；授权失败也需要重新生成挑战。挑战及刷新令牌的竞争在 CloudBase 数据库事务中解决。

成功响应：

```json
{"accessToken":"ma1.session.secret","tokenType":"Bearer","expiresIn":900,"refreshToken":"mr1.session.secret","refreshExpiresIn":2592000,"user":{"id":"u_example","nick":"用户昵称","avatar":null,"avatarUrl":null,"dormBuilding":null}}
```

访问令牌最长 15 分钟；刷新会话绝对寿命 30 天。刷新请求为 `POST /api/mobile/auth/refresh`，内容 `{installationId,refreshToken}`，响应格式同上。刷新同时替换访问和刷新令牌；并发刷新只有一个成功，旧令牌立即失效。客户端必须串行刷新并原子保存整组新凭据。响应丢失后重新授权，不能循环重试旧刷新令牌。

退出使用 `POST /api/mobile/auth/logout`，内容 `{installationId,refreshToken}`。成功后会话立即失效，并清除该会话持有的推送凭据、释放账号绑定；旧会话重复退出不会注销同安装中新会话的登记。客户端清除本地凭据、通知、小组件数据与私有缓存。

当前原生登录仅关联经过验证的已有账号：先验证钉钉 `unionId`，必要时通过钉钉企业通讯录核实 `userId`。不会根据昵称、手机号自动合并用户。旧账号目录仍在最终一致 KV 中，因此首版不在这条链路自动创建/合并账号；未关联账号返回 `409 account_link_required`，由管理员关联。首次注册应在账号目录迁移到事务数据库后开放。

常见错误：`503 mobile_auth_not_configured`、`400 invalid_challenge`、`400 invalid_auth_code`、`409 account_link_required`、`401 invalid_refresh_token`、`403 account_disabled`、`429 mobile_rate_limited`。所有错误均带事件编号。

## 业务接口

原生访问令牌与网站用户令牌经过统一请求鉴权，权限、禁用账号、住宿楼范围、审计和写入校验继续由后端执行。已有接口保持原响应形状：

| 功能 | 接口 |
| --- | --- |
| 本人资料 | `GET /api/auth/me` |
| 按住宿楼查询机器 | `GET /api/machines?building=<partition>&dorm=<code>` |
| 我的洗衣 | `GET /api/tasks` |
| 登记、确认取衣 | 现有 `/api/tasks` 系列 |
| 收藏及提醒设置 | `GET/PATCH /api/user/laundry-preferences` |
| 收藏切换 | `POST /api/user/favorites/toggle` |
| 排队 | 现有 `/api/queue` 系列 |
| 频道 | 现有 `/api/messages` 系列 |
| 朋友、代取 | 现有 `/api/social` 系列 |

服务端状态为准。倒计时归零只能展示预计完成，不能自行把机器状态改为空闲或发送“已完成”的确认通知。

## 推送安装登记

`GET /api/mobile/installations/:installationId` 返回 `{installation:null}` 或公开登记信息。`PUT` 同路径，内容：

```json
{"provider":"fcm","token":"provider-issued-device-token","notificationsEnabled":true,"appVersion":"0.1.1"}
```

服务器支持 `fcm` 与 `upush` 两个互斥的主通道，客户端每台安装实例只使用其中一个。服务端按私有环境变量配置自动发现可用发送器，能力发现只公布已配置的通道。未接入返回 `503 mobile_push_not_configured`。关闭请求为 `{notificationsEnabled:false,appVersion:"0.1.1"}`，会删除推送令牌并取消该安装的待发消息。

响应不包含令牌：`{installation:{installationId,provider,notificationsEnabled,appVersion,updatedAt}}`。原生会话只能操作登录时绑定的安装；访问其他安装返回 403，同时修改发生冲突返回 409。消息使用数据载荷 `{eventId,revision,kind,title,body,taskId,finishAt,serverConfirmed}`，由客户端按 `eventId` 及递增 `revision` 去重。登记接口不发送测试通知；没有发送凭据时不能宣称投递链路已经验收。

## 小组件与刷新

`GET /api/mobile/widget-snapshot` 返回：

```json
{"generatedAt":"2026-01-01T00:00:00.000Z","dormBuilding":null,"tasks":[],"machines":[],"nextRefreshAfterSeconds":300}
```

任务包含 `id,machineId,kind,status,finishAt,note,useDisinfectant,selfCleaned`；机器包含 `id,name,kind,state,status,finishAt` 等公开状态。只返回当前启用住宿楼及该用户获准查看的范围，最多 20 个未取任务，不包含他人的身份、手机号或联系方式。

频道通过原生登录 Bearer 会话访问 `GET /api/messages/realtime?channel=...`，取得限定账号、频道及约两分钟有效期的签名票据。OkHttp 仅连接该接口返回的无查询参数 WSS 地址，使用 `weshirt.channel.v1` 子协议，并声明当前服务 Origin 以满足网关来源名单；Origin 不是身份凭证。握手不携带账号 Bearer，票据只进入首个 `authenticate` 消息，不进入 URL 或日志。服务端仍复核用户、频道权限和连接租约。

连接仅在频道可见且页面恢复时运行，25 秒本地心跳、到期换票、断线指数退避。WS 只传变更信号，正文和媒体仍通过已鉴权 HTTP 补拉。未配置或不可连接时继续前台约 45 秒刷新；已连接时周期完整刷新放宽到两分钟。切换频道、账号、服务或进入后台会取消旧连接，旧会话消息不回填。倒计时只在本地计算。

## CloudBase 原子存储部署

后端源码仓库提供 `packages/core/mobile-store.js` 和 `tools/cloudbase-fn/weshirt-mobile-store/index.mjs`。EdgeOne KV 只有最终一致性，进程内锁不能解决多节点授权码重放，因此不能用它保存/轮换移动会话。

1. 管理员在 CloudBase 创建私有集合 `weshirt_mobile_sessions`，客户端读写权限全部拒绝，仅云函数服务角色访问。为 `expireAt` 增加 TTL 清理索引；鉴权本身在事务内检查毫秒时间，不依赖 TTL 删除时机。
2. 后端仓库运行 `npm ci`、`node tools/cloudbase-fn/build-mobile-store.mjs`，在输出目录 `.cloudbase-build/weshirt-mobile-store` 执行 `npm install --omit=dev`，上传为 Node 22+ HTTP 云函数，入口 `index.main`。构建产物含精确版本的 `@cloudbase/node-sdk` 依赖。
3. 云函数配置 `CLOUDBASE_ENV_ID`、`MOBILE_SESSION_COLLECTION`（可省略）、`MOBILE_ATOMIC_STORE_SECRET`（至少 32 个随机字符）。使用 HTTPS 路由；禁止记录 Authorization 或请求体。内部端点只接受服务端凭据和限定的 `mobile:*` 命名空间，不向 App 开放密钥。
4. EdgeOne 服务端配置相同 `MOBILE_ATOMIC_STORE_SECRET` 和该函数的完整 `MOBILE_ATOMIC_STORE_URL`。可用时自动接入远程原子适配器。Node 直接部署也可通过 `createApp({store,env,mobile:{atomicStore:createCloudBaseMobileAtomicStore(db)}})` 注入 SDK 数据库实例。
5. 服务端另设 `MOBILE_AUTH_ENABLED=true`、`DINGTALK_MOBILE_CLIENT_ID`、`DINGTALK_MOBILE_CLIENT_SECRET`、`DINGTALK_MOBILE_REDIRECT_URI`。在钉钉后台登记正式包名、实际自签证书指纹和回调 URI。密钥仅保留在云环境变量中。
6. 配置下面的真实推送发送器；仅设置 `MOBILE_PUSH_ENABLED=true` 不会启用通道，还须配置对应凭据。无需额外修改 `mobile.pushProviders`，默认服务会自动接线。实际投递仍须凭据和真机验收。

事务适配器采用 SDK 的 `runTransaction`，同一事务内读取当前版本、比对版本、写入随机新版本。不存在或已过期文档只允许 `expectedVersion=null` 创建。数据库/桥接故障直接拒绝授权，不回退最终一致 KV。桥接不自动重试不确定结果；客户端重新登录或重新读取设备设置。

参考：[CloudBase 事务说明](https://docs.cloudbase.net/database/transaction)、[官方 Node SDK 数据库接口](https://github.com/TencentCloudBase/node-sdk/blob/master/docs/database/database.md)。本轮代码交付没有创建数据库、部署云函数或更改线上配置；上述配置完成前能力发现会如实显示未配置。

## 推送发送器配置与验收

后端 `packages/core/mobile-push.js` 提供两条真实 HTTPS 发送路径，使用前面的 CloudBase 原子存储保存用户安装索引和独立待发队列。每用户最多五台安装设备，每台只保留一个主通道。设置 `MOBILE_PUSH_ENABLED=true` 后，服务端按以下凭据自动公布能力：

| 环境变量 | 用途 |
| --- | --- |
| `FCM_SERVICE_ACCOUNT_JSON` | Google 服务账号 JSON，含 `project_id`、`client_email`、PKCS#8 `private_key`；只放服务端私有环境变量 |
| `UMENG_PUSH_APP_KEY` | 与 Android 发行配置相同的友盟 AppKey |
| `UMENG_PUSH_MASTER_SECRET` | 友盟后台的服务端 Master Secret，不能使用客户端 Message Secret 代替，也不能打包到 APK |
| `UMENG_PUSH_PRODUCTION` | 默认 `true`；调试可设 `false`，单播不受测试设备名单限制 |

FCM 使用 RS256 服务账号 JWT 换取一小时 OAuth 访问令牌，权限范围仅为 `firebase.messaging`，再调用 HTTP v1 `projects.messages:send`。仅发送 data payload，由原生客户端统一展示和去重。服务账号须获目标 Firebase 项目的 Cloud Messaging 发送权限，Android 使用同一项目的配置。

友盟使用 `POST https://msgapi.umeng.com/api/send`，对实际发送的 `POST + URL + JSON字节 + MasterSecret` 计算官方规定的 MD5 校验，使用 `display_type=message` 自定义数据。当前 Android 仅接入基础 U-Push；不配置厂商离线转通知，也不宣称杀进程后的厂商离线投递已接通。不启用 U-Push 聚合 FCM，同一安装仅登记 FCM 或基础 U-Push 中的一种。

社区 @、取衣、排队和代取提醒会加入原生独立待发队列；即使钉钉先成功，也不会取消原生通道自己的重试。每次同步开始会继续处理原生队列：同一安装和事件只由一个短期租约发送，失败最多四次并递增退避。每次同步最多并行发送八条，避免发送器串行超时拖慢上游状态查询。`eventId` 来自稳定业务事件标识，`revision` 默认 1；新版本会抑制旧版本重试，客户端继续去重，网络结果不确定时仍可能收到重复数据。消息过期一天后丢弃。排队取消、频道权限变化、任务取衣、账号禁用、关闭通知或退出登录都会阻止相关后续投递。

FCM 明确返回 `UNREGISTERED` 时会原子删除该安装令牌并移除用户索引。普通参数错误、凭据错误或暂时网络错误不会误删设备。友盟单播失败不能一概等同卸载，不根据未知错误码删除令牌；App 退出或关闭系统提醒会主动注销。

与本地提醒合并时，`taskId` 对本人任务为原任务编号，对排队为 `queue:<reservationId>`；`finishAt` 为 ISO 预计结束时间，没有可用时间则为空字符串。可用“任务编号 + 结束时间 + 提前/结束阶段”避免本地计时和服务端推送重复提醒；确认结束可覆盖预计结束，不能反向降级。旧排队记录没有稳定编号时 `taskId` 可为空，不应猜测关联。

待发队列只在私有服务端保存短期通知内容；操作日志不记录消息正文、手机号、设备令牌、OAuth 返回正文或服务端私钥。桥接仅允许 `mobile:*` 内部命名空间，所有发送端点固定为服务商官方 HTTPS 地址并禁止重定向。

单元测试使用本地产生的 RSA 测试密钥、模拟发送服务响应和测试原子存储，验证 JWT 签名、U-Push 字节校验、跨工作节点防重、失败重试、失效令牌清理和频道权限撤回。本轮没有向真实设备或群聊发送测试消息。提供凭据后还需验证FCM 和基础 U-Push 对应设备的前后台接收、通知点击、关闭提醒和卸载重装行为。

参考：[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)、[Google 服务账号 OAuth](https://developers.google.com/identity/protocols/oauth2/service-account)、[友盟服务端消息格式](https://devs.umeng.com/docs/67966/detail/68343)、[友盟签名示例](https://devs.umeng.com/docs/67966/detail/149296)。
