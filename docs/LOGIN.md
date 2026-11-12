# Android 原生钉钉登录

包名为 `com.laoke.weshirt`。客户端使用钉钉官方 `com.alibaba.android:ddopenauth:1.5.0.11`，只拉起已安装且支持统一登录的钉钉客户端。未安装、版本不兼容、服务端未配置时显示明确原因。

## 接入

在钉钉开放平台登记 Android 应用包名和实际签名摘要。自签 release 与 debug 签名不同；测试与私有分发应分别登记，不要通过关闭签名校验绕过。客户端不保存应用 Secret。服务端的移动端应用配置与网站 OAuth 配置分开。

当前移动端契约固定正式包名 `com.laoke.weshirt`，无凭据的 `.debug` APK 用于界面和自动化测试。真实授权使用已登记的正式包与自签证书；若要建立独立的调试授权环境，还需让对应服务端明确支持测试包名与移动应用配置，不能只登记 debug 签名便跳过包名检查。

正式登录还需要服务端启用移动授权、配置独立的 `DINGTALK_MOBILE_CLIENT_ID/CLIENT_SECRET/REDIRECT_URI`，以及可用的 CloudBase 原子会话存储。未配置返回 `503 mobile_auth_not_configured`。本版只登录已经关联钉钉身份的既有账号；未关联用户返回 `409 account_link_required`，需管理员先关联，不能把原生登录成功当作自动注册。部署步骤见 [API.md](API.md)。

官方 SDK 有 H5 降级能力，本项目通过两层限制关闭：启动前检查 `DDAuthUtil.isDDAppInstalled/isDDSupportAPI` 并限定目标包；在 Manifest 删除 SDK 的 `AuthWebviewActivity`。授权回调域名仅用于钉钉校验，不打开页面。若钉钉更新导致原生接口不可用，登录直接失败，用户可重试或更新钉钉。

`DingTalkLoginBridge.createRequest(context, installationId)` 产生随机 state 与 S256 codeChallenge。将其提交后端 challenge 接口。服务端返回 challengeId、原样 state、到期时间，以及 provider 的 clientId、redirectUri、scope。客户端把结果转换成 `NativeLoginChallenge`，调用 `begin(activity, challenge)`。

正式包通过官方约定的实际 `com.laoke.weshirt.ddauth.DDAuthActivity` 接收回调；测试包使用对应 applicationId 的别名。只有已发起且 state 一致的请求能写入结果；重复或错误 state 不会覆盖有效请求。仅接受原生回调和符合服务端长度/字符规则的授权码。临时授权码与 codeVerifier 留在应用私有存储，通过显式 Intent 唤醒主 Activity，Intent 不携带授权材料。

主 Activity 在创建、收到新 Intent 和恢复前台时调用 `consumeReply(context, intent)`。唤醒标记只是提示，恢复任务时即使该标记丢失，已验证的私有结果仍可单次消费。过期、取消、拒绝和服务地址变化会显示中文原因、状态码与事件编号；切换服务地址或退出登录会清除请求，旧服务的结果不能兑换到新服务。返回前台后仍无回调时，等待短窗口再提示重试，同时保留未过期请求以接收延迟结果。

未登录启动、匿名看板刷新和旧会话失效都会清理通知状态。这些自动清理必须保留进行中的授权请求，不能连同 state、PKCE verifier 或回调服务地址一起删除；授权清理只由明确退出、切换服务、重试或单次消费负责。否则用户完成钉钉授权回到应用时，回调会因找不到请求被拒绝。

发起新授权与兑换有效回调前，客户端先隔离旧会话并推进请求代次，保留服务地址和 installationId。旧刷新或旧访问请求迟到的 401 不得取消本轮兑换或清掉新凭据；排队后才运行的受保护请求若已无本地凭据，直接返回带事件编号的本地 401，不发送匿名请求。新会话已有有效 token 时，资料和权限请求直接使用新凭据，不等待旧刷新释放锁。旧会话过期和新授权竞争的窗口由本地 HTTPS 栅栏测试覆盖，包括 exchange 已返回 200、尚未保存新会话的间隙。

后端须校验 installation、包名、state、challenge 的原子单次消费、S256 verifier，并限流。PKCE 绑定的是本项目 challenge/exchange；并不声称钉钉 SDK 自身支持 PKCE。钉钉统一登录返回的 authCode 应使用个人 `oauth2/userAccessToken` 接口兑换，不能交给旧 SNS `sns/getuserinfo_bycode`。

失败与取消只返回错误码。应用不能在日志、崩溃报告、遥测或截图中记录 authCode、codeVerifier、state 或 access token。退出登录会清理未完成请求。应用备份必须排除这些私有数据。

## 回调声明

```xml
<queries><package android:name="com.alibaba.android.rimet" /></queries>
<application>
  <activity android:name=".ddauth.DDAuthActivity"
      android:exported="true" android:excludeFromRecents="true" />
  <activity android:name="com.android.dingtalk.openauth.web.AuthWebviewActivity"
      tools:node="remove" />
</application>
```

根 Manifest 声明 `xmlns:tools="http://schemas.android.com/tools"`。混淆保留 `com.android.dingtalk.openauth.**` 和回调 Activity。debug 源集单独声明 `${applicationId}.ddauth.DDAuthActivity` 别名指向正式回调类，以适配测试包后缀。

## 验收

单元测试验证 S256 标准向量与 state 比较。设备测试使用真实 Intent、私有存储、Activity 生命周期和本地 HTTP 夹具，覆盖无唤醒标记恢复、错误 state 后有效回调、重复回调、过期/取消、缺失回调提示、非法授权码、服务切换、回调组件解析及单次兑换。真实回调组件回归还会在返回前完成一次匿名刷新，确认授权请求未被初始化或同步清理，并检查兑换后资料、权限加载，以及兑换失败时的状态码与事件编号。真机仍需覆盖真实钉钉授权、进程回收和正式签名兼容，不能将夹具测试等同于已在用户手机上完成授权。

参考：[钉钉 Android 应用授权登录接入流程](https://open.dingtalk.com/document/orgapp/android-platform-application-authorization-login-access)、[Maven Central 官方 SDK](https://repo.maven.apache.org/maven2/com/alibaba/android/ddopenauth/)。
