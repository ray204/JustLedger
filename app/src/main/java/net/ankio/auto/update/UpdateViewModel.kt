package net.ankio.auto.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.auto.BuildConfig
import java.io.File

enum class UpdatePhase { CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateUi(val phase: UpdatePhase = UpdatePhase.CHECKING, val release: UpdateRelease? = null,
                    val message: String = "正在检查更新…", val progress: Int = 0, val file: File? = null)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    val store = UpdateStore(application)
    private val mutable = MutableStateFlow(UpdateUi())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    init { restoreOrCheck() }

    private fun run(action: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(phase = UpdatePhase.ERROR,
                    message = (e as? UpdateFailure)?.message ?: "暂时无法更新，请稍后重试")
            }
        }
    }
    private fun restoreOrCheck() = run {
        val pending = store.pending()
        if (pending != null && UpdatePolicy.isNewer(pending.version, BuildConfig.VERSION_NAME)) monitor(pending)
        else { withContext(Dispatchers.IO) { store.clear() }; checkFeed() }
    }
    fun check() = run { checkFeed() }
    fun recheck() = run {
        mutable.value = UpdateUi()
        withContext(Dispatchers.IO) { store.clear() }
        checkFeed()
    }
    private suspend fun checkFeed() {
        mutable.value = UpdateUi()
        val result = withContext(Dispatchers.IO) { UpdateFeed().latest(BuildConfig.VERSION_NAME) }
        mutable.value = if (result == null) UpdateUi(UpdatePhase.CURRENT, message = "已是最新正式版")
            else UpdateUi(UpdatePhase.AVAILABLE, result, "发现新版本 ${result.version}")
    }
    fun download() {
        val release = mutable.value.release ?: return
        run {
            mutable.value = UpdateUi(UpdatePhase.DOWNLOADING, release, "正在准备下载…")
            withContext(Dispatchers.IO) { store.start(release) }
            monitor(release)
        }
    }
    private suspend fun monitor(release: UpdateRelease) {
        while (true) {
            when (val status = withContext(Dispatchers.IO) { store.status(release) }) {
                is UpdateStatus.Downloading -> mutable.value = UpdateUi(UpdatePhase.DOWNLOADING, release,
                    if (status.waiting) "等待网络或系统调度…" else "正在下载 ${status.percent}%", status.percent)
                is UpdateStatus.Ready -> { mutable.value = UpdateUi(UpdatePhase.READY, release, "下载完成，已通过校验", 100, status.file); return }
                is UpdateStatus.Failed -> { mutable.value = UpdateUi(UpdatePhase.ERROR, release, status.message); return }
            }
            delay(700)
        }
    }
    fun notice(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun installFailed(message: String) {
        mutable.value = mutable.value.copy(phase = UpdatePhase.ERROR, message = message, file = null)
    }
    fun cancel() {
        work?.cancel()
        // Wait for in-flight IO before removing its files.
        val previous = work
        work = viewModelScope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { store.clear() }
            val release = mutable.value.release
            mutable.value = UpdateUi(UpdatePhase.AVAILABLE, release, "下载已取消")
        }
    }
}
