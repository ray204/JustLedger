package org.ezbook.server.tools

import java.math.BigDecimal
import java.math.RoundingMode

/** Pure rules shared by recognition and persistence; time/amount alone never prove identity. */
object LedgerPolicy {
    data class Evidence(
        val amount: Double, val currency: String, val book: String, val type: String,
        val namespace: String = "", val transactionId: String = "", val refundId: String = "",
        val sourceKey: String = "", val account: String = ""
    )

    fun minorUnits(amount: Double): Long {
        require(amount.isFinite() && amount > 0) { "金额必须为大于零的有效数字" }
        val units = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        require(units > 0) { "金额不能小于最小记账单位" }
        return units
    }

    fun sameTransaction(a: Evidence, b: Evidence): Boolean {
        if (a.type != b.type || a.book != b.book || a.currency != b.currency) return false
        if (minorUnits(a.amount) != minorUnits(b.amount)) return false
        val generic = setOf("", "微信", "支付宝", "银行卡", "零钱", "余额")
        if (a.account !in generic && b.account !in generic && a.account != b.account) return false
        if (a.sourceKey.isNotBlank() && a.sourceKey == b.sourceKey) return true
        if (a.namespace.isBlank() || a.namespace != b.namespace) return false
        // A payment may have several equal partial refunds; payment ID is NOT a refund ID.
        if (a.type == "IncomeRefund") return a.refundId.isNotBlank() && a.refundId == b.refundId
        return a.transactionId.isNotBlank() && a.transactionId == b.transactionId
    }

    fun canTransfer(a: Evidence, b: Evidence, from: String, to: String, owned: Set<String>): Boolean {
        if (a.type !in setOf("Income", "Expend", "Transfer") || b.type !in setOf("Income", "Expend", "Transfer")) return false
        if (from.isBlank() || to.isBlank() || from == to || from !in owned || to !in owned) return false
        if (a.currency != b.currency || a.book != b.book || minorUnits(a.amount) != minorUnits(b.amount)) return false
        return a.namespace.isNotBlank() && a.namespace == b.namespace &&
            a.transactionId.isNotBlank() && a.transactionId == b.transactionId
    }

    fun validateRefund(original: Double, alreadyRefunded: Double, refund: Double) {
        val total = minorUnits(original)
        require(alreadyRefunded.isFinite() && alreadyRefunded >= 0) { "已退款金额无效" }
        val used = if (alreadyRefunded == 0.0) 0L else minorUnits(alreadyRefunded)
        require(used <= total && minorUnits(refund) <= total - used) { "退款累计金额超过原账单可退金额" }
    }
}
