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
    private var categories = emptyList<LedgerChartData.Slice>()
    private var selectedSlice = -1
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
        plot.id = R.id.ledger_chart_plot
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
        bills = items.filter(LedgerChartData::counted)
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
        val updated = LedgerChartData.categories(selected, currency)
        if (updated != categories) { selectedSlice = -1; plot.resetSelection() }
        categories = updated
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
        plot.updateDescription()
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
                drawPie(canvas, w, h)
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
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var progress = 0f
        private var animator: android.animation.ValueAnimator? = null
        private val labelBounds = mutableMapOf<Int, RectF>()
        private fun radius() = minOf(dp(55).toFloat(), width * .185f)
        fun resetSelection() { animator?.cancel(); progress = 0f }
        fun updateDescription() {
            val selected = categories.getOrNull(selectedSlice)
            contentDescription = if (pie) {
                if (selected == null) "分类支出，共 ${money(categories.sumOf { it.cents })} $currency。" +
                    categories.joinToString { "${it.name} ${money(it.cents)}" } + "。点击扇区或分类查看金额，再次点击恢复总额"
                else "已选 ${selected.name}，${money(selected.cents)} $currency，占比 ${percent(selected.cents)}。再次点击恢复总额"
            } else days.joinToString { "${it.label} 支出 ${money(it.expense)} 收入 ${money(it.income)}" }
        }
        private fun percent(cents: Long) = String.format(Locale.getDefault(), "%.1f%%", cents * 100.0 / categories.sumOf { it.cents }.coerceAtLeast(1))
        private fun select(index: Int) {
            selectedSlice = LedgerChartData.toggle(selectedSlice, index)
            animator?.cancel()
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
            updateDescription()
            announceForAccessibility(contentDescription)
            invalidate()
        }
        private fun fit(text: String, maxWidth: Float): String {
            var result = text
            while (result.isNotEmpty() && paint.measureText(result) > maxWidth) result = result.dropLast(1)
            return if (result == text) text else result.dropLast(1) + "…"
        }
        private fun drawPie(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2; val cy = h / 2; val r = radius()
            val total = categories.sumOf { it.cents }.coerceAtLeast(1)
            val ring = dp(21).toFloat()
            data class Anchor(val index: Int, val x: Float, val y: Float, val right: Boolean)
            val anchors = mutableListOf<Anchor>()
            var start = -90f
            labelBounds.clear()
            categories.forEachIndexed { i, entry ->
                val sweep = entry.cents.toFloat() / total * 360
                val angle = Math.toRadians((start + sweep / 2).toDouble())
                val cos = kotlin.math.cos(angle).toFloat(); val sin = kotlin.math.sin(angle).toFloat()
                val emphasis = if (i == selectedSlice) progress else 0f
                val offset = dp(5) * emphasis
                val arcRadius = r - ring / 2 + dp(2) * emphasis
                val x = cx + cos * offset; val y = cy + sin * offset
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ring + dp(3) * emphasis
                paint.color = colors[i % colors.size]
                // Tiny categories retain their true size; do not inflate them with a minimum angle.
                val gap = if (categories.size == 1) 0f else minOf(2f, sweep * .15f)
                canvas.drawArc(RectF(x-arcRadius, y-arcRadius, x+arcRadius, y+arcRadius), start+gap/2, sweep-gap, false, paint)
                val edge = r + dp(5) + offset
                anchors += Anchor(i, cx + cos * edge, cy + sin * edge, cos >= 0)
                start += sweep
            }
            paint.style = Paint.Style.FILL
            // Labels sit beside their own sectors. No color-key legend is required to identify a category.
            for (right in listOf(false, true)) {
                val side = anchors.filter { it.right == right }.sortedBy { it.y }
                val ys = LedgerChartData.labelPositions(side.map { it.y }, dp(20).toFloat(), h-dp(20), dp(29).toFloat())
                side.forEachIndexed { j, anchor ->
                    val y = ys[j]; val elbow = cx + (r+dp(13)) * if (right) 1 else -1
                    val end = if (right) w-dp(4) else dp(4).toFloat()
                    paint.color = if (anchor.index == selectedSlice) ink else muted
                    paint.strokeWidth = dp(1).toFloat().coerceAtLeast(1f)
                    canvas.drawLine(anchor.x, anchor.y, elbow, y, paint)
                    canvas.drawLine(elbow, y, end, y, paint)
                    canvas.drawCircle(anchor.x, anchor.y, dp(2).toFloat(), paint)
                    paint.typeface = if (anchor.index == selectedSlice) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    paint.textSize = 11 * resources.displayMetrics.scaledDensity
                    paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
                    canvas.drawText(fit(categories[anchor.index].name, kotlin.math.abs(end-elbow)), end, y-dp(5), paint)
                    labelBounds[anchor.index] = RectF(minOf(elbow,end), y-dp(24), maxOf(elbow,end), y+dp(5))
                }
            }
            val selected = categories.getOrNull(selectedSlice)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            paint.color = muted; paint.textSize = 10 * resources.displayMetrics.scaledDensity
            canvas.drawText(fit(selected?.name ?: "总支出", (r-ring)*1.8f), cx, cy-dp(17), paint)
            paint.color = ink; paint.textSize = 16 * resources.displayMetrics.scaledDensity
            paint.typeface = Typeface.DEFAULT_BOLD
            val amount = money(selected?.cents ?: total)
            while (paint.measureText(amount) > (r-ring)*1.85f && paint.textSize > dp(9)) paint.textSize -= 1
            canvas.drawText(amount, cx, cy+dp(5), paint)
            paint.typeface = Typeface.DEFAULT; paint.color = muted; paint.textSize = 10 * resources.displayMetrics.scaledDensity
            canvas.drawText(selected?.let { percent(it.cents) } ?: currency, cx, cy+dp(23), paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.hypot(event.x-downX, event.y-downY) > slop) dragging = true
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    performClick()
                    if (pie) {
                        val label = labelBounds.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                        select(label ?: LedgerChartData.hit(categories, event.x-width/2, event.y-height/2,
                            radius()-dp(23), radius()+dp(12)))
                    } else if (days.isNotEmpty()) {
                        val d = days[(event.x / width * days.size).toInt().coerceIn(0, days.lastIndex)]
                        android.widget.Toast.makeText(context, "${d.label} · 支出 ${money(d.expense)} · 收入 ${money(d.income)} $currency", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = true
            }
            return true
        }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (pie && categories.isNotEmpty()) {
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
        }
        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            if (pie && categories.isNotEmpty()) {
                when (action) {
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { select((selectedSlice+1)%categories.size); return true }
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { select(if (selectedSlice <= 0) categories.lastIndex else selectedSlice-1); return true }
                }
            }
            return super.performAccessibilityAction(action, arguments)
        }
        override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
