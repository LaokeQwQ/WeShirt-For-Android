# 桌面小组件管理

「我的 → 桌面小组件 → 管理与添加小组件」展示 2×1、2×2、4×2 三种规格及系统返回的已添加数量。回到应用时重新读取数量，不通过轮询或接口请求更新。

当前桌面支持 Android 标准固定组件接口时，按钮调用 `AppWidgetManager.requestPinAppWidget`，由桌面显示确认页。接口返回已接受请求不能等同于添加成功；用户取消不增加数量。不支持时保留完整手动指引。应用不能代替桌面移除组件，移除需在桌面长按完成。

指引先按当前桌面应用识别，其次使用公开的品牌信息；第三方或未知桌面使用通用 Android 步骤。用户可手动选择 OriginOS、HyperOS/MIUI、ColorOS、realme UI、OxygenOS、HarmonyOS/EMUI（运行 Android 版的系统）、MagicOS、One UI 和通用指引。菜单名以实际系统为准。

管理页不修改提供者、登录状态、洗衣记录或通知许可，也不读取设备标识。组件绘制与后台同步见 [hyperos-widgets.md](hyperos-widgets.md)。

## 参考

- [Android 应用内添加组件](https://developer.android.com/develop/ui/views/appwidgets/discoverability)
- [Android 桌面组件操作](https://support.google.com/android/answer/9450271)
- [vivo 官方用户手册，桌面组件章节](https://asia-exstatic-vivofs.vivo.com/PSee2l50xoirPK7y/1786593747020/565bb78fce0fedcfc3ef3a6600490dc7.pdf)
- [小米添加、移动和移除小部件](https://www.mi.com/uk/support/article/KA-33142/)
- [OPPO 官方支持：Find and Add Widgets](https://support.oppo.com/uk/answer/?curPage=8&selModelsId=Smartphones)
- [realme 添加桌面组件](https://www.realme.com/global/support/kw/doc/2077559)
- [一加官方用户手册，Widgets 章节](https://service.oneplus.com/content/dam/support/user-manuals/common/OnePlus_11_5G_User_Manual_EN.pdf)
- [华为添加、移动和删除窗口小工具](https://consumer.huawei.com/cn/support/content/zh-cn15978796/)
- [荣耀经典小工具入口](https://www.honor.com/cn/support/content/zh-cn00409513/)
- [三星添加和移除桌面组件](https://www.samsung.com/ae/support/mobile-devices/how-to-create-and-delete-widgets-in-galaxy-device/)
