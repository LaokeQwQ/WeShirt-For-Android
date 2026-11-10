# WeShirt for Android

![WeShirt](docs/hero.svg)

[English](README.en.md) · [开发文档](docs/DEVELOPMENT.md) · [服务端项目](https://github.com/LaokeQwQ/WeShirt)

宿舍洗衣房的原生安卓客户端。看看楼下哪台机器空闲，登记一趟洗衣，等提醒来取衣。

WeShirt 基于海乐生活公开数据提供洗衣机状态服务。客户端使用 Kotlin、Jetpack Compose Material 3 和 Filament，3D 场景与页面均为原生实现。服务端沿用腾讯 CloudBase 与腾讯 EdgeOne Pages。

## 可以做什么

- 按住宿楼查看机器，列表按最快可用排序，也可以在 3D 洗衣房里直接选机。
- 登记洗衣、烘干和六分钟桶自洁；公开展示备注、消毒液和自清洁记录。
- 收藏常用机器、加入排队、接收洗衣提醒，用桌面小组件查看进度。
- 在公共频道和自己的楼栋交流，回复、举报、长按头像提及对方，发送图片和短视频。
- 扫码添加朋友、分享邀请、请朋友代取，确认后开启衣物私聊。
- 切换配色、深浅色和 3D 性能模式。自动模式根据实际帧耗时、温度和省电状态调整。

## 安装与接入

支持 Android 10 及以上。正式包名为 `com.laoke.weshirt`，使用自签名私有分发。首次打开填写你使用的服务地址并选择住宿楼，之后自动记住。

钉钉原生登录、FCM、友盟及小米/华为推送需要部署方登记包名、签名并提供私有配置。未接通的渠道会显示实际状态；预计倒计时到零也不会代替机器确认完成。接入步骤见 [开发文档](docs/DEVELOPMENT.md)。

## 致谢

感谢 [PA733 / Laundry-Status-Monitor](https://github.com/PA733/Laundry-Status-Monitor) 提供的基础轮子支持。

MIT License. Made by Laoke.
