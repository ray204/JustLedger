package net.ankio.auto.ui.components

import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.tools.LedgerPolicy
import kotlin.math.atan2
import kotlin.math.hypot

/** Shared chart semantics; money is accumulated in minor units, never across currencies. */
internal object LedgerChartData {
    data class Slice(val name: String, val cents: Long)
    fun category(bill: BillInfoModel) = bill.cateName.trim().ifBlank { "未分类" }
    fun matches(bill: BillInfoModel, category: String?) = category == null || category(bill) == category
    fun counted(bill: BillInfoModel) = bill.groupId == -1L && bill.state != BillState.Wait2Edit &&
        !bill.hasFlag(BillInfoModel.FLAG_NOT_COUNT)
    fun categories(bills: List<BillInfoModel>, currency: String): List<Slice> {
        val all = bills.filter { counted(it) && it.type == BillType.Expend && it.currencyModel().code == currency }
            .groupBy(::category).map { (name, rows) ->
                Slice(name, rows.sumOf { runCatching { LedgerPolicy.minorUnits(it.money) }.getOrDefault(0).coerceAtLeast(0) })
            }.filter { it.cents > 0 }.sortedWith(compareByDescending<Slice> { it.cents }.thenBy { it.name })
        return if (all.size > 5) all.take(4) + Slice("其余${all.size - 4}类", all.drop(4).sumOf { it.cents }) else all
    }
    /** Angle from 12 o'clock, clockwise. The center hole and outside are reset targets. */
    fun hit(slices: List<Slice>, x: Float, y: Float, innerRadius: Float, outerRadius: Float): Int {
        val radius = hypot(x, y)
        if (radius < innerRadius || radius > outerRadius || slices.isEmpty()) return -1
        val angle = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 450) % 360
        val total = slices.sumOf { it.cents }.toDouble()
        if (total <= 0) return -1
        var end = 0.0
        slices.forEachIndexed { i, slice ->
            end += slice.cents / total * 360
            if (angle < end) return i
        }
        return slices.lastIndex
    }
    fun toggle(selected: Int, hit: Int) = if (selected == hit) -1 else hit

    /** Keep leader labels ordered on each side without overlap, including tiny adjacent slices. */
    fun labelPositions(anchors: List<Float>, top: Float, bottom: Float, gap: Float): List<Float> {
        if (anchors.isEmpty()) return emptyList()
        val spacing = if (anchors.size == 1) 0f else minOf(gap, (bottom - top) / (anchors.size - 1))
        val result = anchors.map { it.coerceIn(top, bottom) }.toMutableList()
        for (i in 1..result.lastIndex) result[i] = maxOf(result[i], result[i - 1] + spacing)
        if (result.last() > bottom) {
            result[result.lastIndex] = bottom
            for (i in result.lastIndex - 1 downTo 0) result[i] = minOf(result[i], result[i + 1] - spacing)
        }
        return result
    }
}
