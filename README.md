# WeShirt for Android

![WeShirt](docs/hero.svg)

[English](README.en.md) · [开发文档](docs/DEVELOPMENT.md) · [服务端项目](https://github.com/LaokeQwQ/WeShirt)

宿舍洗衣房的原生安卓客户端。看看楼下哪台机器空闲，登记一趟洗衣，等提醒来取衣。

WeShirt 基于海乐生活公开数据提供洗衣机状态服务。客户端使用 Kotlin、Jetpack Compose Material 3 和 Filament，3D 场景与页面均为原生实现。服务端沿用腾讯 CloudBase 与腾讯 EdgeOne Pages。

## 可以做什么

- 按住宿楼查看洗衣机和烘干机，可用机器优先。2D 默认列表，也能切换卡片；有场景的楼栋可进入与 Web 布局一致的原生 3D。
- 登记程序或自定义时长，公开展示备注、消毒液和洗衣前自清洁记录；机器留言与 Web 同步。
- 收藏常用机器、加入排队、接收洗衣提醒，用桌面小组件查看进度。
- 在公共频道和自己的楼栋交流，回复、举报、长按头像提及对方，发送图片和短视频。
- 扫码添加朋友、分享邀请、请朋友代取，确认后开启衣物私聊。
- 切换配色、深浅色和 3D 性能模式。自动模式根据实际帧耗时、温度和省电状态调整。
- 使用洗衣助手、记忆和 Skills；管理员可在原生后台处理公告、楼栋、权限、巡检、通知和审计。

## 安装与接入

支持 Android 10 及以上。正式包名为 `com.laoke.weshirt`，使用自签名私有分发。发行包固定连接部署方的服务，首次选择住宿楼后自动记住。

钉钉原生登录、FCM 和基础 U-Push 推送需要部署方登记应用并提供私有配置。公开源码不含凭据，正式授权和消息投递仍需完成真机验收。未接通的渠道会显示实际状态；预计倒计时到零也不会代替机器确认完成。接入步骤见 [开发文档](docs/DEVELOPMENT.md)。

Web 功能的原生入口与平台差异见 [功能对照](docs/native-parity.md)。

## 致谢

感谢 [PA733 / Laundry-Status-Monitor](https://github.com/PA733/Laundry-Status-Monitor) 提供的基础轮子支持。

MIT License. Made by Laoke.
