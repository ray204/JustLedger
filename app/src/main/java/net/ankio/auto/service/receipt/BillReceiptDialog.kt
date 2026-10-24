package net.ankio.auto.service.receipt

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.gson.Gson
import net.ankio.auto.R
import net.ankio.auto.databinding.DialogBillReceiptBinding
import net.ankio.auto.http.api.BillAPI
import net.ankio.auto.ui.api.BaseSheetDialog
import net.ankio.auto.utils.BillTool
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.db.model.BillNoticeModel
import org.ezbook.server.tools.MD5HashTable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One persistent window; browsing never acknowledges an entry. */
class BillReceiptDialog internal constructor(context: Context) : BaseSheetDialog<DialogBillReceiptBinding>(context) {
    private var notices: List<BillNoticeModel> = emptyList()
    private var confirming = false
    private var onEdit: (Long) -> Unit = {}
    private val pages = object : RecyclerView.Adapter<Page>() {
        override fun getItemCount() = notices.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Page = Page(parent.context)
        override fun onBindViewHolder(holder: Page, position: Int) = holder.bind(notices[position])
    }

    fun configure(edit: (Long) -> Unit): BillReceiptDialog = apply {
        onEdit = edit
        binding.receiptPager.adapter = pages
        binding.receiptPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateControls() }
        })
        binding.receiptPrevious.setOnClickListener { binding.receiptPager.currentItem -= 1 }
        binding.receiptNext.setOnClickListener { binding.receiptPager.currentItem += 1 }
        binding.receiptEdit.setOnClickListener { current()?.let { onEdit(it.billId) } }
        binding.receiptConfirm.setOnClickListener {
            val notice = current() ?: return@setOnClickListener
            if (confirming) return@setOnClickListener
            confirming = true
            binding.receiptError.visibility = View.GONE
            updateControls()
            launch {
                try {
                    BillAPI.acknowledgeNotice(notice.id, MD5HashTable.md5(notice.billJson))
                    // Keep the same window open for the remaining cards.
                    confirming = false
                    updateNotices(BillAPI.pendingNotices())
                    BillReceiptManager.refresh()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    confirming = false
                    binding.receiptError.text = context.getString(R.string.receipt_ack_failed)
                    binding.receiptError.visibility = View.VISIBLE
                    updateControls()
                    BillReceiptManager.refresh()
                }
            }
        }
    }

    fun focusBill(billId: Long) {
        val index = notices.indexOfFirst { it.billId == billId }
        if (index >= 0) binding.receiptPager.setCurrentItem(index, false)
    }

    private fun current() = notices.getOrNull(binding.receiptPager.currentItem)

    fun updateNotices(updated: List<BillNoticeModel>) {
        if (confirming) return
        if (updated.isEmpty()) { dismiss(); return }
        val oldId = current()?.id
        val oldIndex = binding.receiptPager.currentItem
        val changed = notices.map { it.id to it.billJson } != updated.map { it.id to it.billJson }
        if (changed) {
            notices = updated.toList()
            pages.notifyDataSetChanged()
            val index = updated.indexOfFirst { it.id == oldId }.takeIf { it >= 0 }
                ?: oldIndex.coerceAtMost(updated.lastIndex)
            binding.receiptPager.setCurrentItem(index, false)
        }
        updateControls()
    }

    private fun updateControls() {
        val index = binding.receiptPager.currentItem
        binding.receiptQueue.text = if (notices.size > 1) "${index + 1} / ${notices.size} · 左右滑动查看" else "1 笔待确认"
        binding.receiptPrevious.isEnabled = !confirming && index > 0
        binding.receiptNext.isEnabled = !confirming && index < notices.lastIndex
        binding.receiptPrevious.visibility = if (notices.size > 1) View.VISIBLE else View.INVISIBLE
        binding.receiptNext.visibility = binding.receiptPrevious.visibility
        binding.receiptEdit.isEnabled = !confirming
        binding.receiptConfirm.isEnabled = !confirming
        binding.receiptPager.isUserInputEnabled = !confirming
        binding.receiptTitle.setText(if (current()?.kind == "UPDATED") R.string.receipt_updated_title else R.string.receipt_title)
    }

    override fun show() {
        super.show(cancel = false)
        setCanceledOnTouchOutside(false)
        behavior.isDraggable = false
        behavior.isHideable = false
    }

    private class Page(context: Context) : RecyclerView.ViewHolder(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }) {
        private val root = itemView as LinearLayout
        private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(root.context).apply {
            textSize = size
            setTextColor(ContextCompat.getColor(context, color))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (7 * resources.displayMetrics.density).toInt() })
        }
        private val amount = text(32f, R.color.ledger_primary, true)
        private val merchant = text(19f, R.color.ledger_ink, true)
        private val item = text(14f, R.color.ledger_muted)
        private val account = text(14f, R.color.ledger_muted)
        private val time = text(13f, R.color.ledger_muted)
        fun bind(notice: BillNoticeModel) {
            val bill = Gson().fromJson(notice.billJson, BillInfoModel::class.java)
            val code = bill.currencyModel().code.ifBlank { "CNY" }
            amount.text = if (code == "CNY") "¥${BillTool.formatAmount(bill.money)}" else "${BillTool.formatAmount(bill.money)} $code"
            merchant.text = bill.shopName.ifBlank { bill.shopItem }.ifBlank { "未提供商户" }
            item.text = bill.shopItem
            item.visibility = if (bill.shopItem.isBlank() || bill.shopItem == bill.shopName) View.GONE else View.VISIBLE
            account.text = "${bill.type.toChineseString()} · ${bill.accountNameFrom.ifBlank { "未提供账户" }}"
            time.text = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(bill.time))
        }
    }
}
