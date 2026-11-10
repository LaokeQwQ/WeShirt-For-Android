# Android 原生钉钉登录

包名为 `com.laoke.weshirt`。客户端使用钉钉官方 `com.alibaba.android:ddopenauth:1.5.0.11`，只拉起已安装且支持统一登录的钉钉客户端。未安装、版本不兼容、服务端未配置时显示明确原因。

## 接入

在钉钉开放平台登记 Android 应用包名和实际签名摘要。自签 release 与 debug 签名不同；测试与私有分发应分别登记，不要通过关闭签名校验绕过。客户端不保存应用 Secret。服务端的移动端应用配置与网站 OAuth 配置分开。

官方 SDK 有 H5 降级能力，本项目通过两层限制关闭：启动前检查 `DDAuthUtil.isDDAppInstalled/isDDSupportAPI` 并限定目标包；在 Manifest 删除 SDK 的 `AuthWebviewActivity`。授权回调域名仅用于钉钉校验，不打开页面。若钉钉更新导致原生接口不可用，登录直接失败，用户可重试或更新钉钉。

`DingTalkLoginBridge.createRequest(context, installationId)` 产生随机 state 与 S256 codeChallenge。将其提交后端 challenge 接口。服务端返回 challengeId、原样 state、到期时间，以及 provider 的 clientId、redirectUri、scope。客户端把结果转换成 `NativeLoginChallenge`，调用 `begin(activity, challenge)`。

钉钉回调通过 `com.laoke.weshirt.ddauth.DDAuthActivity` 的 activity-alias 交给 `DingTalkCallbackActivity`。本地仅接受已发起、未消费、未过期且 state 一致的回调。临时授权码与 codeVerifier 留在应用私有存储；主 Activity 收到布尔唤醒标记后调用 `consumeReply(context, intent)`，只可读取一次，再通过后端 exchange 接口兑换应用会话。

后端须校验 installation、包名、state、challenge 的原子单次消费、S256 verifier，并限流。PKCE 绑定的是本项目 challenge/exchange；并不声称钉钉 SDK 自身支持 PKCE。钉钉统一登录返回的 authCode 应使用个人 `oauth2/userAccessToken` 接口兑换，不能交给旧 SNS `sns/getuserinfo_bycode`。

失败与取消只返回错误码。应用不能在日志、崩溃报告、遥测或截图中记录 authCode、codeVerifier、state 或 access token。退出登录会清理未完成请求。应用备份必须排除这些私有数据。

## 回调声明

```xml
<queries><package android:name="com.alibaba.android.rimet" /></queries>
<application>
  <activity android:name=".platform.login.DingTalkCallbackActivity" android:exported="false" />
  <activity-alias android:name=".ddauth.DDAuthActivity"
      android:targetActivity=".platform.login.DingTalkCallbackActivity"
      android:exported="true" />
  <activity android:name="com.android.dingtalk.openauth.web.AuthWebviewActivity"
      tools:node="remove" />
</application>
```

根 Manifest 声明 `xmlns:tools="http://schemas.android.com/tools"`。混淆保留 `com.android.dingtalk.openauth.**` 和回调 Activity。回调别名必须与最终 applicationId 对应。

## 验收

单元测试验证 S256 标准向量与 state 比较。真机还需覆盖未装钉钉、过旧客户端、授权取消、正常授权、重复回调、过期回调、退出后旧回调、进程回收、不同签名的正式包。没有登记好的移动应用和真机时，不能将 SDK 编译成功视为登录已经联通。

参考：[钉钉 Android 应用授权登录接入流程](https://dingtalk.apifox.cn/doc-3522093)、[Maven Central 官方 SDK](https://repo.maven.apache.org/maven2/com/alibaba/android/ddopenauth/)。
