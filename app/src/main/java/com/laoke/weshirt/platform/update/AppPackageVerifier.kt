package com.laoke.weshirt.platform.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

internal object AppPackageVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, release: AppRelease) {
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw AppUpdateException("APK_UNREADABLE", "无法读取安装包信息")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (candidate.packageName != context.packageName || candidate.longVersionCode != release.versionCode ||
            candidate.longVersionCode <= installed.longVersionCode || candidate.versionName != release.versionName) {
            throw AppUpdateException("APK_PACKAGE_MISMATCH", "安装包的应用或版本信息不符")
        }
        val signers = candidate.signers()
        if (signers.isEmpty() || signers != installed.signers())
            throw AppUpdateException("APK_SIGNATURE_MISMATCH", "安装包签名与当前应用不一致")
    }

    private fun PackageInfo.signers() = signingInfo?.apkContentsSigners?.toSet().orEmpty()
}
