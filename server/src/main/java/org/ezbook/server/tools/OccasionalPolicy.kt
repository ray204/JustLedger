package org.ezbook.server.tools

import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel

/** Occasional expenses remain real expenses; only the daily view excludes them. */
object OccasionalPolicy {
    enum class Scope(val label: String) { DAILY("日常"), OCCASIONAL("偶发"), ALL("全部") }
    fun isOccasional(bill: BillInfoModel): Boolean {
        if (bill.type !in setOf(BillType.Expend, BillType.IncomeRefund)) return false
        if (bill.hasFlag(BillInfoModel.FLAG_DAILY)) return false
        if (bill.hasFlag(BillInfoModel.FLAG_OCCASIONAL)) return true
        return bill.cateName.split('-', '－', '/', '／', '>', '＞', '·').any { it.trim() == "房租" }
    }
    fun setOccasional(bill: BillInfoModel, enabled: Boolean) {
        bill.setFlag(BillInfoModel.FLAG_OCCASIONAL, enabled)
        bill.setFlag(BillInfoModel.FLAG_DAILY, !enabled)
    }
    fun matches(bill: BillInfoModel, scope: Scope) = when (scope) {
        Scope.ALL -> true
        Scope.DAILY -> !isOccasional(bill)
        Scope.OCCASIONAL -> isOccasional(bill)
    }
    /** Refunds follow their original payment, even if it is in a previous month or later reclassified. */
    fun withOriginal(bill: BillInfoModel, original: BillInfoModel?): BillInfoModel {
        if (bill.type != BillType.IncomeRefund || original == null || original.type != BillType.Expend ||
            original.id != bill.originalBillId || original.bookName != bill.bookName || original.currencyCode() != bill.currencyCode()) return bill
        return bill.copy().apply { setOccasional(this, isOccasional(original)) }
    }
    fun counted(bill: BillInfoModel, currency: String) = bill.groupId == -1L && bill.state != BillState.Wait2Edit &&
        !bill.hasFlag(BillInfoModel.FLAG_NOT_COUNT) && bill.currencyCode() == currency

    data class Totals(val income: Long, val dailyExpense: Long, val occasionalExpense: Long) {
        val totalExpense get() = dailyExpense + occasionalExpense
        fun asMap() = mapOf("income" to income / 100.0, "expense" to dailyExpense / 100.0,
            "totalExpense" to totalExpense / 100.0, "occasionalExpense" to occasionalExpense / 100.0)
    }
    fun totals(rows: List<BillInfoModel>, currency: String): Totals {
        var income = 0L; var daily = 0L; var occasional = 0L
        rows.filter { counted(it, currency) }.forEach { bill ->
            val cents = runCatching { LedgerPolicy.minorUnits(bill.money) }.getOrDefault(0)
            when (bill.type) {
                BillType.Income -> income += cents
                BillType.Expend, BillType.IncomeRefund -> {
                    val value = if (bill.type == BillType.IncomeRefund) -cents else cents
                    if (isOccasional(bill)) occasional += value else daily += value
                }
                else -> Unit
            }
        }
        return Totals(income, daily, occasional)
    }
}
