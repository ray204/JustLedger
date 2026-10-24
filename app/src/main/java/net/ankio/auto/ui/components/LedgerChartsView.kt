package net.ankio.auto.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.ankio.auto.R
import net.ankio.auto.utils.BillTool
import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.tools.LedgerPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local descriptive charts. Currencies are kept separate; pending/merged rows never count. */
class LedgerChartsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val ink = ContextCompat.getColor(context, R.color.ledger_ink)
    private val muted = ContextCompat.getColor(context, R.color.ledger_muted)
    private val colors = intArrayOf(0xff356855.toInt(), 0xff85a794.toInt(), 0xffd2ab76.toInt(), 0xff8d99b2.toInt(), 0xffb6bdb9.toInt())
    private var bills = emptyList<BillInfoModel>()
    private var currency = "CNY"
    private var pie = true
    private var categories = emptyList<Pair<String, Long>>()
    private var days = emptyList<Day>()
    private val totalLabel = label(14f, ink)
    private val detail = label(12f, muted)
    private val currencyButton = label(12f, colors[0])
    private val pieButton = label(14f, ink)
    private val barButton = label(14f, muted)
    private val plot = Plot(context)
    private data class Day(val label: String, val income: Long, val expense: Long)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(ContextCompat.getColor(context, R.color.ledger_surface)); cornerRadius = dp(24).toFloat() }
        val tabs = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        pieButton.text = "分类占比"
        barButton.text = "收支趋势"
        pieButton.setPadding(0, dp(8), dp(16), dp(8))
        barButton.setPadding(0, dp(8), dp(16), dp(8))
        tabs.addView(pieButton); tabs.addView(barButton)
        tabs.addView(currencyButton, LayoutParams(0, dp(40), 1f))
        currencyButton.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        addView(tabs)
        addView(totalLabel)
        addView(plot, LayoutParams(-1, dp(184)))
        addView(detail)
        pieButton.setOnClickListener { pie = true; render() }
        barButton.setOnClickListener { pie = false; render() }
        currencyButton.setOnClickListener {
            val available = bills.map { it.currencyModel().code.ifBlank { "CNY" } }.distinct().sorted()
            if (available.size > 1) { currency = available[(available.indexOf(currency) + 1) % available.size]; render() }
        }
        render()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float, color: Int) = TextView(context).apply { textSize = size; setTextColor(color); maxLines = 1 }
    private fun money(cents: Long) = BillTool.formatAmount(cents / 100.0)
    private fun cents(bill: BillInfoModel) = runCatching { LedgerPolicy.minorUnits(bill.money) }.getOrDefault(0)

    fun setBills(items: List<BillInfoModel>) {
        bills = items.filter { it.groupId == -1L && it.state != BillState.Wait2Edit && !it.hasFlag(BillInfoModel.FLAG_NOT_COUNT) }
        val available = bills.map { it.currencyModel().code.ifBlank { "CNY" } }.distinct()
        if (available.isNotEmpty() && currency !in available) currency = available.first()
        render()
    }

    private fun render() {
        val selected = bills.filter { it.currencyModel().code.ifBlank { "CNY" } == currency }
        val expense = selected.filter { it.type == BillType.Expend }
        val income = selected.filter { it.type == BillType.Income }.sumOf { cents(it) }
        val refund = selected.filter { it.type == BillType.IncomeRefund }.sumOf { cents(it) }
        val gross = expense.sumOf { cents(it) }
        val allCategories = expense.groupBy { it.cateName.ifBlank { "未分类" } }.map { (name, rows) -> name to rows.sumOf { cents(it) } }.sortedByDescending { it.second }
        categories = if (allCategories.size > 5) allCategories.take(4) + ("其他分类" to allCategories.drop(4).sumOf { it.second }) else allCategories
        val span = selected.map { it.time }.let { if (it.isEmpty()) 0L else it.max() - it.min() }
        val formatter = SimpleDateFormat(if (span > 32L * 86400000) "yyyy-MM" else "MM-dd", Locale.getDefault())
        days = selected.filter { it.type == BillType.Income || it.type == BillType.Expend }
            .sortedBy { it.time }.groupBy { formatter.format(Date(it.time)) }.map { (day, rows) -> Day(day,
                rows.filter { it.type == BillType.Income }.sumOf { cents(it) },
                rows.filter { it.type == BillType.Expend }.sumOf { cents(it) }) }
        currencyButton.text = if (bills.map { it.currencyModel().code }.distinct().size > 1) "$currency ▾" else currency
        currencyButton.contentDescription = "统计币种 $currency，点击切换，不同币种分别统计"
        totalLabel.visibility = if (pie) View.GONE else View.VISIBLE
        val totals = "● 支出 ${money(gross)}   ● 收入 ${money(income)}"
        totalLabel.text = android.text.SpannableString(totals).apply {
            setSpan(android.text.style.ForegroundColorSpan(colors[0]), 0, 1, 0)
            val incomeDot = totals.lastIndexOf('●')
            setSpan(android.text.style.ForegroundColorSpan(colors[2]), incomeDot, incomeDot + 1, 0)
        }
        plot.layoutParams.height = dp(if (pie) 184 else 144)
        plot.requestLayout()
        detail.text = if (refund > 0) "已退款 ${money(refund)} · 净支出 ${money(gross - refund)}" else ""
        detail.visibility = if (refund > 0) View.VISIBLE else View.GONE
        detail.gravity = android.view.Gravity.CENTER

        pieButton.setTextColor(if (pie) colors[0] else muted)
        barButton.setTextColor(if (pie) muted else colors[0])
        pieButton.typeface = if (pie) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        barButton.typeface = if (pie) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        plot.contentDescription = totalLabel.text.toString() + "; " + if (pie) categories.joinToString { "${it.first} ${money(it.second)}" } else days.joinToString { "${it.label} 支出 ${money(it.expense)} 收入 ${money(it.income)}" }
        plot.invalidate()
    }

    private inner class Plot(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.style = Paint.Style.FILL
            if ((pie && categories.isEmpty()) || (!pie && days.isEmpty())) {
                paint.color = muted; paint.textSize = dp(13).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText(if (pie) "暂无支出" else "暂无收支", w / 2, h / 2, paint); return
            }
            paint.textAlign = Paint.Align.LEFT
            if (pie) {
                val diameter = minOf(dp(120).toFloat(), w * .52f)
                val cx = w / 2; val cy = dp(70).toFloat()
                val bounds = RectF(cx-diameter/2, cy-diameter/2, cx+diameter/2, cy+diameter/2)
                val total = categories.sumOf { it.second }.coerceAtLeast(1)
                var start = -90f
                categories.forEachIndexed { i, entry ->
                    val sweep = entry.second.toFloat() / total * 360
                    paint.color = colors[i % colors.size]
                    canvas.drawArc(bounds, start + .8f, (sweep - 1.6f).coerceAtLeast(.2f), true, paint)
                    start += sweep
                }
                paint.color = ContextCompat.getColor(context, R.color.ledger_surface)
                canvas.drawCircle(cx, cy, diameter * .36f, paint)
                paint.textAlign = Paint.Align.CENTER
                paint.color = muted; paint.textSize = dp(10).toFloat()
                canvas.drawText("支出", cx, cy-dp(7), paint)
                paint.color = ink; paint.textSize = dp(17).toFloat(); paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(money(total), cx, cy+dp(16), paint)
                paint.typeface = Typeface.DEFAULT
                val columns = minOf(3, categories.size)
                categories.forEachIndexed { i, entry ->
                    val colWidth = w / columns; val x = (i % columns + .5f)*colWidth
                    val y = dp(155 + (i / columns)*23).toFloat()
                    val name = if (entry.first.length > 4) entry.first.take(4)+"…" else entry.first
                    val text = "$name ${String.format(Locale.getDefault(), "%.0f%%", entry.second * 100.0 / total)}"
                    paint.textSize = dp(10).toFloat()
                    paint.color = colors[i]; canvas.drawCircle(x - paint.measureText(text)/2 - dp(7), y-dp(3), dp(3).toFloat(), paint)
                    paint.color = ink; canvas.drawText(text, x, y, paint)
                }
            } else {
                paint.textAlign = Paint.Align.LEFT
                val max = days.maxOf { maxOf(it.expense, it.income) }.coerceAtLeast(1)
                val baseline = h - dp(25); val top = dp(22).toFloat(); val column = w / days.size
                paint.color = muted; paint.textSize = dp(10).toFloat()
                canvas.drawText(money(max), 0f, dp(12).toFloat(), paint)
                days.forEachIndexed { i, day ->
                    val x = i * column; val bar = (column * .3f).coerceAtMost(dp(14).toFloat())
                    listOf(day.expense, day.income).forEachIndexed { j, value ->
                        paint.color = if (j == 0) colors[0] else colors[2]
                        val left = x + column/2 + (j-1)*bar
                        canvas.drawRoundRect(left, baseline - value.toFloat()/max*(baseline-top), left+bar*.8f, baseline, dp(2).toFloat(), dp(2).toFloat(), paint)
                    }
                    if (i == 0 || i == days.lastIndex || (days.size > 5 && i == days.size/2)) {
                        paint.color = muted; paint.textSize = dp(10).toFloat()
                        paint.textAlign = if (days.size == 1) Paint.Align.CENTER else when(i) { 0 -> Paint.Align.LEFT; days.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                        canvas.drawText(day.label, (x+column/2).coerceIn(dp(2).toFloat(), w-dp(2)), h-dp(5), paint)
                    }
                }
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                performClick()
                if (!pie && days.isNotEmpty()) {
                    val d = days[(event.x / width * days.size).toInt().coerceIn(0, days.lastIndex)]
                    android.widget.Toast.makeText(context, "${d.label} · 支出 ${money(d.expense)} · 收入 ${money(d.income)} $currency", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
