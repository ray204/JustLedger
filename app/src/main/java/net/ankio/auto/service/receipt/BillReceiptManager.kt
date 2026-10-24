package net.ankio.auto.service.receipt

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ankio.auto.R
import net.ankio.auto.autoApp
import net.ankio.auto.http.api.BillAPI
import net.ankio.auto.service.CoreService
import net.ankio.auto.ui.activity.HomeActivity
import net.ankio.auto.ui.api.BaseSheetDialog
import net.ankio.auto.storage.Logger

/** The database is the queue. A visible dialog is only a view of its oldest unacknowledged receipt. */
object BillReceiptManager {
    private const val CHANNEL = "ledger_receipts"
    private const val NOTIFICATION = 21001
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshLock = Mutex()
    private var activity: Activity? = null
    private var service: CoreService? = null
    private var dialog: BillReceiptDialog? = null
    private var dialogHost: Context? = null
    private var editing = false
    private var editingBillId = 0L
    private var polling: Job? = null
    private var lastCount = -1

    fun attach(host: Activity) { activity = host; start() }
    fun attach(host: CoreService) { service = host; start() }
    fun detach(host: Context) {
        if (activity === host) activity = null
        if (service === host) service = null
        if (dialogHost === host) {
            dialog?.dismiss() // Lifecycle interruption is not an acknowledgement.
            dialog = null
            dialogHost = null
        }
        if (activity == null && service == null) { polling?.cancel(); polling = null }
    }
    private fun start() {
        if (polling?.isActive == true) { refresh(); return }
        polling = scope.launch {
            while (isActive) { load(); delay(2000) }
        }
    }
    fun refresh() { scope.launch { load() } }

    private suspend fun load() = refreshLock.withLock {
        try {
            val pending = BillAPI.pendingNotices()
            showNotification(pending.size)
            if (editing) return@withLock
            if (dialog?.isShowing == true) {
                dialog?.updateNotices(pending)
                return@withLock
            }
            if (pending.isEmpty()) return@withLock
            val keyguard = autoApp.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val power = autoApp.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (keyguard.isKeyguardLocked || !power.isInteractive) return@withLock
            val host: Context = activity?.takeUnless { it.isDestroyed || it.isFinishing }
                ?: service?.takeIf { Settings.canDrawOverlays(it) } ?: return@withLock
            dialogHost = host
            val next = BaseSheetDialog.create<BillReceiptDialog>(host).configure { billId ->
                editing = true
                editingBillId = billId
                dialog?.dismiss()
                try {
                    if (activity is HomeActivity) (activity as HomeActivity).editReceiptBill(billId)
                    else host.startActivity(Intent(host, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(HomeActivity.EDIT_RECEIPT_BILL, billId))
                } catch (e: Exception) {
                    editing = false
                    Logger.e("Cannot open receipt editor", e)
                    refresh()
                }
            }
            dialog = next
            next.setOnDismiss {
                if (dialog === next) { dialog = null; dialogHost = null }
            }
            next.updateNotices(pending)
            next.focusBill(editingBillId)
            editingBillId = 0L
            next.show()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.d("Receipt delivery deferred: ${e.message}")
        }
    }

    fun beginEditing() { editing = true; dialog?.dismiss() }
    fun finishEditing() { editing = false; refresh() }

    private fun showNotification(count: Int) {
        val manager = NotificationManagerCompat.from(autoApp)
        if (count == 0) { manager.cancel(NOTIFICATION); lastCount = 0; return }
        val native = autoApp.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        native.createNotificationChannel(NotificationChannel(CHANNEL, autoApp.getString(R.string.receipt_channel), NotificationManager.IMPORTANCE_HIGH))
        if (!manager.areNotificationsEnabled()) return // Still pending; HomeActivity will show the dialog.
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                autoApp, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (count == lastCount && native.activeNotifications.any { it.id == NOTIFICATION }) return
        val open = PendingIntent.getActivity(autoApp, NOTIFICATION, Intent(autoApp, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(NOTIFICATION, NotificationCompat.Builder(autoApp, CHANNEL)
                .setSmallIcon(R.drawable.icon_auto)
                .setContentTitle(autoApp.getString(R.string.receipt_notification_title))
                .setContentText(autoApp.getString(R.string.receipt_notification_body, count))
                .setContentIntent(open).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            lastCount = count
        }
    }
}
