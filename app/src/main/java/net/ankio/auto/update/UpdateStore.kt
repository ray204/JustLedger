package net.ankio.auto.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import net.ankio.auto.BuildConfig
import java.io.File
import java.security.MessageDigest

sealed class UpdateStatus {
    data class Downloading(val percent: Int, val waiting: Boolean) : UpdateStatus()
    data class Ready(val file: File) : UpdateStatus()
    data class Failed(val message: String) : UpdateStatus()
}

class UpdateStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("justledger_updates", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(DownloadManager::class.java)
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) { prefs.edit().putBoolean("auto", value).apply() }
    fun claimAutoCheck(now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong("lastCheck", 0)
        if (!UpdatePolicy.autoCheckDue(autoCheck, last, now)) return false
        return prefs.edit().putLong("lastCheck", now).commit()
    }
    fun pending(): UpdateRelease? = runCatching {
        Gson().fromJson(prefs.getString("release", null), UpdateRelease::class.java)?.let(UpdatePolicy::validate)
    }.getOrNull()
    fun start(release: UpdateRelease) {
        UpdatePolicy.validate(release)
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) throw UpdateFailure("当前安装包仅支持 arm64 设备")
        clear()
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("JustLedger ${release.version}").setDescription("下载更新，完成后回到 App 安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, "updates", "JustLedger-${release.version}.apk")
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
        val id = manager.enqueue(request)
        if (!prefs.edit().putLong("download", id).putString("release", Gson().toJson(release)).commit()) {
            manager.remove(id); throw UpdateFailure("无法保存下载任务，请重试")
        }
    }
    fun clear() {
        val id = prefs.getLong("download", -1)
        if (id >= 0) manager.remove(id)
        app.getExternalFilesDir("updates")?.listFiles()?.forEach { it.delete() }
        File(app.cacheDir, "updates").deleteRecursively()
        prefs.edit().remove("download").remove("release").commit()
    }
    fun status(release: UpdateRelease): UpdateStatus {
        if (!UpdatePolicy.isNewer(release.version, BuildConfig.VERSION_NAME)) { clear(); return UpdateStatus.Failed("更新已完成，请重新检查更新") }
        return manager.query(DownloadManager.Query().setFilterById(prefs.getLong("download", -1))).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use UpdateStatus.Failed("下载任务已失效，请重新下载")
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val source = File(app.getExternalFilesDir("updates"), "JustLedger-${release.version}.apk")
                    val target = File(app.cacheDir, "updates/JustLedger-${release.version}.apk")
                    try {
                        if (!source.isFile) throw UpdateFailure("安装包已清理，请重新下载")
                        UpdatePolicy.verifiedCopy(source.inputStream(), target, release)
                        validateApk(target, release)
                        UpdateStatus.Ready(target)
                    } catch (e: Exception) {
                        target.delete()
                        UpdateStatus.Failed((e as? UpdateFailure)?.message ?: "安装包不可用，请重新下载")
                    }
                }
                DownloadManager.STATUS_FAILED -> UpdateStatus.Failed("下载失败，请检查网络和剩余空间后重试")
                else -> {
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val paused = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_RUNNING
                    UpdateStatus.Downloading((bytes * 100 / release.size).toInt().coerceIn(0, 100), paused)
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    fun validateApk(file: File, release: UpdateRelease) {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateFailure("安装包无法读取，请重新下载")
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }?.toSet().orEmpty()
        UpdatePolicy.validateIdentity(archive.packageName, archive.versionName, archive.longVersionCode, signers(archive),
            app.packageName, installed.longVersionCode, signers(installed), release)
    }
    fun installIntent(file: File) = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("JustLedger 更新", uri)
    }
}
