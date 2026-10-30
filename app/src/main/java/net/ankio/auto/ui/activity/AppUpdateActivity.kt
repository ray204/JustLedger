package net.ankio.auto.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.auto.BuildConfig
import net.ankio.auto.databinding.ActivityAppUpdateBinding
import net.ankio.auto.ui.api.BaseActivity
import net.ankio.auto.update.*

class AppUpdateActivity : BaseActivity() {
    private val vm: UpdateViewModel by viewModels()
    private lateinit var binding: ActivityAppUpdateBinding
    private var installAfterDownload = false
    private var installing = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installReady()
        else vm.notice("未允许安装，可点击安装更新后再授权")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installAfterDownload = savedInstanceState?.getBoolean("installAfterDownload") ?: false
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.currentVersion.text = "当前版本 ${BuildConfig.VERSION_NAME}"
        binding.updateAction.setOnClickListener {
            when (vm.state.value.phase) {
                UpdatePhase.AVAILABLE -> { installAfterDownload = true; vm.download() }
                UpdatePhase.READY -> requestInstall()
                UpdatePhase.ERROR -> if (vm.state.value.release != null) { installAfterDownload = true; vm.download() } else vm.check()
                UpdatePhase.CURRENT -> vm.check()
                else -> Unit
            }
        }
        binding.updateCancel.setOnClickListener {
            installAfterDownload = false
            if (vm.state.value.phase == UpdatePhase.DOWNLOADING) vm.cancel() else vm.recheck()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.state.collect { state ->
                    render(state)
                    if (state.phase == UpdatePhase.READY && installAfterDownload) {
                        installAfterDownload = false
                        requestInstall()
                    }
                    if (state.phase == UpdatePhase.ERROR) installAfterDownload = false
                }
            }
        }
    }
    private fun render(state: UpdateUi) {
        binding.updateTitle.text = state.release?.let { "JustLedger ${it.version}" } ?: when (state.phase) {
            UpdatePhase.CURRENT -> "已是最新版本"
            UpdatePhase.ERROR -> "检查未完成"
            else -> "正在检查更新…"
        }
        binding.updateStatus.text = state.message
        binding.updateNotes.text = state.release?.notes?.trim().orEmpty().ifBlank { if (state.release != null) "体验与稳定性更新" else "" }
        binding.updateNotes.visibility = if (state.release != null) View.VISIBLE else View.GONE
        val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)
        binding.updateProgress.visibility = if (busy) View.VISIBLE else View.GONE
        // Material disallows switching indeterminate -> determinate while visibly animating.
        if (binding.updateProgress.isIndeterminate != (state.phase == UpdatePhase.CHECKING)) {
            binding.updateProgress.visibility = View.INVISIBLE
            binding.updateProgress.isIndeterminate = state.phase == UpdatePhase.CHECKING
            binding.updateProgress.visibility = if (busy) View.VISIBLE else View.GONE
        }
        if (!binding.updateProgress.isIndeterminate) binding.updateProgress.setProgressCompat(state.progress, true)
        binding.updateAction.isEnabled = !busy && !installing
        binding.updateAction.text = when (state.phase) {
            UpdatePhase.AVAILABLE -> "一键更新 · ${state.release?.size?.let { (it + 1024 * 1024 - 1) / (1024 * 1024) }} MB"
            UpdatePhase.DOWNLOADING -> "正在下载…"
            UpdatePhase.READY -> "安装更新"
            UpdatePhase.ERROR -> if (state.release != null) "重新下载" else "重新检查"
            UpdatePhase.CHECKING -> "正在检查…"
            UpdatePhase.CURRENT -> "再次检查"
        }
        binding.updateCancel.text = if (state.phase == UpdatePhase.DOWNLOADING) "取消下载" else "重新检查"
        binding.updateCancel.visibility = if (state.release != null) View.VISIBLE else View.GONE
        binding.updateCancel.isEnabled = state.phase != UpdatePhase.CHECKING && !installing
    }
    private fun requestInstall() {
        if (vm.state.value.phase != UpdatePhase.READY || installing) return
        if (!packageManager.canRequestPackageInstalls()) {
            vm.notice("请允许 JustLedger 安装应用，返回后继续")
            try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { vm.notice("无法打开安装权限设置，请在系统设置中允许此来源") }
        } else installReady()
    }
    private fun installReady() {
        val state = vm.state.value
        val file = state.file ?: return
        val release = state.release ?: return
        if (installing) return
        installing = true
        binding.updateAction.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { vm.store.validateApk(file, release) }
                startActivity(vm.store.installIntent(file))
                vm.notice("请在系统页面确认安装；取消后可再次点击安装更新")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { vm.installFailed((e as? UpdateFailure)?.message ?: "无法打开安装页面，请重新下载后重试") }
            finally { installing = false; binding.updateAction.isEnabled = true }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("installAfterDownload", installAfterDownload)
        super.onSaveInstanceState(outState)
    }
}
