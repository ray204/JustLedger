package org.ezbook.server.tools

import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import kotlin.math.abs

/** Suggestions only: never merge an amount/time resemblance without confirmation. */
object PossibleDuplicatePolicy {
    const val WINDOW_MS = 300_000L
    fun plausible(source: BillInfoModel, target: BillInfoModel): Boolean {
        if (!EnrichmentPolicy.compatible(source, target) || source.time <= 0 || target.time <= 0 ||
            abs(source.time - target.time) > WINDOW_MS) return false
        if (source.type == BillType.IncomeRefund && (source.refundId.isBlank() || source.refundId != target.refundId)) return false
        val a=ReceiptIdentity.keys(source); val b=ReceiptIdentity.keys(target)
        if (a.keys.intersect(b.keys).any { a[it] != b[it] }) return false
        fun clean(s: String) = s.replace(Regex("\\s+"), "").replace('（','(').replace('）',')')
        val generic=setOf("", "美团", "微信", "微信支付", "支付宝", "银行卡", "未知商户", "未提供商户")
        fun merchant(s: String) = clean(s).takeUnless { it in generic || it.endsWith("银行") }.orEmpty()
        val shopA=merchant(source.shopName); val shopB=merchant(target.shopName)
        if (shopA.isNotEmpty() && shopB.isNotEmpty() && shopA != shopB) return false
        if (source.shopItem.isNotBlank() && target.shopItem.isNotBlank() && clean(source.shopItem) != clean(target.shopItem)) return false
        // Wider bank/SMS timestamp differences need another common fact. Bare amount + time
        // is only suggested within one minute, and still never silently merged.
        val sameShop=shopA.isNotEmpty() && shopA == shopB
        val sameAccount=source.accountNameFrom.isNotBlank() && source.accountNameFrom !in generic &&
            clean(source.accountNameFrom) == clean(target.accountNameFrom)
        return abs(source.time - target.time) <= 60_000L || sameShop || sameAccount
    }
}
