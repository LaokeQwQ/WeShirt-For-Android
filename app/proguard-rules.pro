-keep class com.alibaba.android.dingtalk.** { *; }
-keep class com.android.dingtalk.openauth.** { *; }
-keep class com.umeng.** { *; }
-keep class com.uc.** { *; }
-keep class org.android.agoo.** { *; }
# MiPush ships pre-obfuscated classes; preserve their method bodies from R8 optimization.
-keep class com.xiaomi.** { *; }
-keep public class * extends com.xiaomi.mipush.sdk.PushMessageReceiver { *; }
-keep class com.google.android.filament.** { *; }
-keep class com.laoke.weshirt.platform.push.StrictPlatformTls { *; }
-dontwarn com.umeng.**
-dontwarn com.uc.**

# HMS NetworkKit optionally uses Huawei ROM classes, with compatibility checks and
# Throwable fallbacks to standard Android/JDK implementations on other devices.
-dontwarn android.telephony.HwTelephonyManager
-dontwarn com.huawei.android.os.BuildEx$VERSION
-dontwarn com.huawei.libcore.io.ExternalStorageFile
-dontwarn com.huawei.libcore.io.ExternalStorageFileInputStream
-dontwarn com.huawei.libcore.io.ExternalStorageFileOutputStream
-dontwarn com.huawei.libcore.io.ExternalStorageRandomAccessFile

# HMS stats probes this optional analytics SDK before use. It is intentionally
# absent; com.huawei.hms.client.bi.setting=true disables the retained BI bridge.
# Keep these exact names; do not hide missing dependencies across all HMS packages.
-dontwarn com.huawei.hianalytics.process.HiAnalyticsConfig$Builder
-dontwarn com.huawei.hianalytics.process.HiAnalyticsConfig
-dontwarn com.huawei.hianalytics.process.HiAnalyticsInstance$Builder
-dontwarn com.huawei.hianalytics.process.HiAnalyticsInstance
-dontwarn com.huawei.hianalytics.process.HiAnalyticsManager
-dontwarn com.huawei.hianalytics.util.HiAnalyticTools
