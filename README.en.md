# WeShirt for Android

![WeShirt](docs/hero.svg)

[简体中文](README.md) · [Development](docs/DEVELOPMENT.md) · [Backend](https://github.com/LaokeQwQ/WeShirt)

A native Android companion for the laundry room downstairs. Find an available machine, register your laundry and receive a pickup reminder.

Built with Kotlin, Jetpack Compose Material 3 and Filament. Both the interface and the interactive 3D room are native. WeShirt uses public machine-status data from 海乐生活, with a backend on Tencent CloudBase and EdgeOne Pages.

Machines are grouped into washers and dryers, with available machines first. The 2D board opens as a list and can switch to cards. Supported buildings also have a native 3D room matching the Web layout; unavailable scenes fall back to the board without a startup error.

Registration supports wash programs, custom durations and public notes, disinfectant and pre-wash self-cleaning records. Machine discussions are shared with the Web app. Other features include favorites, queues, community channels, media attachments, friend invitations, pickup requests, native notifications and a home screen widget. Themes support light, dark and custom colors. The 3D renderer adapts to measured frame cost, thermal conditions and battery saver.

Requires Android 10 or later. Package: `com.laoke.weshirt`. Releases are privately distributed with a self-signed certificate. Enter your service URL and choose your building on first launch.

Native DingTalk login, FCM and basic U-Push require application registration and private configuration. Unconfigured providers are reported as unavailable. Estimated timers never replace confirmed machine status.

See [login prerequisites](docs/LOGIN.md) and [push setup](docs/PUSH.md) before distributing a configured build. Real DingTalk authorization and message delivery require registered applications and device acceptance; the public source includes no credentials.

Thanks to [PA733 / Laundry-Status-Monitor](https://github.com/PA733/Laundry-Status-Monitor) for the original foundation.

MIT License. Made by Laoke.
