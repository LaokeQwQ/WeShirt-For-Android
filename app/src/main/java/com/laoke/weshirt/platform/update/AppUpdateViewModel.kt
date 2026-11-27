package com.laoke.weshirt.platform.update

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoke.weshirt.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class AppUpdateState(
    val release: AppRelease? = null,
    val ignored: Boolean = false,
    val checking: Boolean = false,
    val progress: Float? = null,
    val ready: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

internal class AppUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val client = AppUpdateClient(BuildConfig.API_BASE_URL)
    val enabled = !BuildConfig.DEBUG && client.configured
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private val preferences = app.getSharedPreferences("app_updates", 0)
    private val updates = File(app.cacheDir, "app-updates")
    private var nextAutoCheck = 0L
    private var verifiedApk: File? = null

    fun check(automatic: Boolean = false) {
        val current = mutableState.value
        val now = SystemClock.elapsedRealtime()
        if (!enabled || current.checking || current.progress != null || (automatic && now < nextAutoCheck)) return
        mutableState.update {
            if (automatic) it.copy(checking = true) else it.copy(checking = true, status = null, error = null)
        }
        viewModelScope.launch {
            try {
                val (checked, release) = withContext(Dispatchers.IO) {
                    val installed = BuildConfig.VERSION_CODE.toLong()
                    val lastCurrent = preferences.getLong("confirmed_current_at", 0L)
                    val cloudCheckEnabled = !automatic || preferences.getLong("confirmed_current_build", 0L) != installed ||
                        System.currentTimeMillis() - lastCurrent !in 0L until CHECK_INTERVAL
                    if (!cloudCheckEnabled) return@withContext false to null
                    val candidate = client.check(installed)
                    val edit = preferences.edit()
                    if (candidate == null) edit.putLong("confirmed_current_at", System.currentTimeMillis())
                        .putLong("confirmed_current_build", installed)
                    else edit.remove("confirmed_current_at").remove("confirmed_current_build")
                    edit.apply()
                    true to candidate
                }
                nextAutoCheck = now + CHECK_INTERVAL
                if (checked) {
                    val sameRelease = release != null && release == current.release
                    if (!sameRelease) verifiedApk = null
                    mutableState.update {
                        it.copy(release = release, ready = sameRelease && it.ready,
                            ignored = release?.versionCode == preferences.getLong("ignored_release", -1L),
                            status = if (!automatic && release == null) "当前已是最新版本" else null, error = null)
                    }
                }
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                nextAutoCheck = now + RETRY_INTERVAL
                val detail = describe(problem, "CHECK_FAILED", "无法检查更新，请稍后重试")
                if (!automatic) mutableState.update { it.copy(error = detail) }
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    fun ignoreCurrentRelease() {
        val current = mutableState.value
        val release = current.release ?: return
        if (current.progress != null) return
        preferences.edit().putLong("ignored_release", release.versionCode).apply()
        mutableState.update { it.copy(ignored = true, status = "已忽略此版本，可在这里手动安装。") }
    }

    fun download() {
        val current = mutableState.value
        val release = current.release ?: return
        if (current.checking || current.progress != null || current.ready) return
        mutableState.update { it.copy(progress = 0f, status = null, error = null) }
        viewModelScope.launch {
            try {
                verifiedApk = withContext(Dispatchers.IO) {
                    val apk = client.download(release, updates) { progress ->
                        mutableState.update { it.copy(progress = progress) }
                    }
                    try {
                        verifyPackage(apk, release)
                    } catch (problem: Exception) {
                        apk.delete()
                        throw problem
                    }
                    apk
                }
                mutableState.update { it.copy(ready = true) }
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                val detail = describe(problem, "DOWNLOAD_FAILED", "下载更新失败，请稍后重试")
                mutableState.update { it.copy(error = detail) }
            } finally {
                mutableState.update { it.copy(progress = null) }
            }
        }
    }

    fun install(activity: Activity) {
        val current = mutableState.value
        val apk = verifiedApk
        if (current.checking || current.progress != null || !current.ready || apk == null) return
        if (!apk.isFile) {
            verifiedApk = null
            val detail = describe(AppUpdateException("APK_EVICTED", "安装包已被清理，请重新下载"))
            mutableState.update { it.copy(ready = false, error = detail) }
            return
        }
        try {
            val packages = app.packageManager
            if (!packages.canRequestPackageInstalls()) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                mutableState.update { it.copy(status = "开启「允许来自此来源的应用」，返回后再点一次安装更新。", error = null) }
                return
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.media", apk)
            val installer = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val resolved = packages.queryIntentActivities(installer, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.activityInfo ?: throw AppUpdateException("INSTALLER_UNAVAILABLE", "设备没有可用的系统安装器")
            installer.component = ComponentName(resolved.packageName, resolved.name)
            activity.startActivity(installer)
            mutableState.update { it.copy(status = null, error = null) }
        } catch (problem: Exception) {
            val detail = describe(problem, "INSTALLER_FAILED", "无法打开系统安装确认")
            mutableState.update { it.copy(error = detail) }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, release: AppRelease) {
        val manager = app.packageManager
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw AppUpdateException("APK_UNREADABLE", "无法读取安装包信息")
        val installed = manager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (candidate.packageName != app.packageName || candidate.longVersionCode != release.versionCode ||
            candidate.longVersionCode <= installed.longVersionCode || candidate.versionName != release.versionName) {
            throw AppUpdateException("APK_PACKAGE_MISMATCH", "安装包的应用或版本信息不符")
        }
        val signers = candidate.signers()
        if (signers.isEmpty() || signers != installed.signers()) {
            throw AppUpdateException("APK_SIGNATURE_MISMATCH", "安装包签名与当前应用不一致")
        }
    }

    private fun PackageInfo.signers() = signingInfo?.apkContentsSigners?.toSet().orEmpty()

    private fun describe(problem: AppUpdateException) = describe(problem, problem.code, problem.message.orEmpty())

    private fun describe(problem: Exception, fallbackCode: String, fallbackMessage: String): String {
        val updateError = problem as? AppUpdateException
        val event = "ota_" + UUID.randomUUID().toString().replace("-", "")
        val code = updateError?.code ?: fallbackCode
        Log.w("WeShirtUpdate", "$event $code ${problem.javaClass.simpleName}")
        return "${updateError?.message ?: fallbackMessage}（$code，事件 $event）"
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        const val RETRY_INTERVAL = 30 * 60 * 1000L
    }
}
