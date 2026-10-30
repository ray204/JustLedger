package net.ankio.auto.update

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.auto.BuildConfig
import net.ankio.auto.ui.activity.AppUpdateActivity
import net.ankio.auto.ui.activity.HomeActivity

/** Only JustLedger releases; no upstream licensing, channels or download endpoints. */
object AppUpdateHelper {
    fun checkOnOpen(activity: HomeActivity) {
        if (!UpdateStore(activity).claimAutoCheck()) return
        activity.lifecycleScope.launch {
            val update = try { withContext(Dispatchers.IO) { UpdateFeed().latest(BuildConfig.VERSION_NAME) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return@launch }
            if (update != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                Snackbar.make(activity.findViewById(android.R.id.content), "发现新版本 ${update.version}", Snackbar.LENGTH_LONG)
                    .setAction("查看") { activity.startActivity(Intent(activity, AppUpdateActivity::class.java)) }.show()
            }
        }
    }
}
