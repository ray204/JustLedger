package org.ezbook.server.tools

import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import kotlin.math.abs

/** Matching a refund never relies on equal amounts: its amount may be only part of a purchase. */
object RefundMatchPolicy {
    data class Candidate(val bill: BillInfoModel, val remaining: Double)
    private val generic = setOf("", "美团", "微信", "微信支付", "支付宝", "银行", "其他", "其它", "未知商户", "未提供商户")
    fun clean(value: String) = value.lowercase().replace(Regex("[\\s\\p{P}]+"), "")
    private fun shop(b: BillInfoModel) = clean(b.shopName).takeUnless { it in generic }.orEmpty()
    private fun item(b: BillInfoModel) = clean(b.shopItem).takeUnless {
        it.length < 2 || it in generic || Regex(".*订单[0-9a-z]{8,}.*").matches(it)
    }.orEmpty()
    private fun account(b: BillInfoModel) = clean(b.accountNameFrom.ifBlank { b.accountNameTo })
    private fun channel(b: BillInfoModel): String {
        val value=clean(b.channel+" "+b.transactionNamespace+" "+b.app)
        return when {
            listOf("微信","财付通","wechat","comtencentmm").any { value.contains(it) } -> "wechat"
            listOf("支付宝","alipay").any { value.contains(it) } -> "alipay"
            else -> clean(b.channel)
        }
    }
    private fun originalAmount(b:BillInfoModel):Long? {
        val details=ReceiptDetails.read(b.detailsJson)
        val value=listOf("原支付金额","原实付金额").firstNotNullOfOrNull { key ->
            details.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        } ?: return null
        val text=value.trim().removePrefix("￥").removePrefix("¥").removeSuffix("元").trim()
        if (!text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
        return runCatching { LedgerPolicy.minorUnits(text.toDouble()) }.getOrNull()
    }
    private fun originalTime(b:BillInfoModel):Long? {
        val details=ReceiptDetails.read(b.detailsJson)
        val value=listOf("原支付时间","原付款时间","支付时间","付款时间").firstNotNullOfOrNull { key ->
            details.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        } ?: return null
        if (!value.matches(Regex("[0-9]{13}")) && !Regex("[0-9]{1,2}[:：时][0-9]{1,2}").containsMatchIn(value)) return null
        return runCatching { DateUtils.toEpochMillis(value) }.getOrNull()
    }
    private fun scope(b: BillInfoModel): String {
        val ns = b.transactionNamespace.ifBlank { b.app }.lowercase()
        return when {
            ns == "com.tencent.mm" || ns.startsWith("wechat") -> "wechat"
            ns == "com.eg.android.alipaygphone" || ns.startsWith("alipay") -> "alipay"
            else -> ns
        }
    }
    private fun orderKeys(b: BillInfoModel) = ReceiptIdentity.keys(b).filterKeys {
        it.endsWith("|order") || it.endsWith("|merchant-order")
    }
    fun exact(refund: BillInfoModel, original: BillInfoModel): Boolean {
        if (refund.originalTransactionId.isNotBlank() && ReceiptIdentity.keys(original)[scope(refund)+"|payment"] ==
            ReceiptIdentity.normalize(refund.originalTransactionId)) return true
        if (refund.originalTransactionId.isNotBlank() && scope(refund).isNotBlank() && scope(refund) == scope(original) &&
            ReceiptIdentity.normalize(refund.originalTransactionId) == ReceiptIdentity.normalize(original.transactionId)) return true
        val originals = orderKeys(original)
        return orderKeys(refund).any { (key,id) -> originals[key] == id }
    }
    fun conflicts(refund: BillInfoModel, original: BillInfoModel): Boolean {
        if (refund.originalTransactionId.isNotBlank() && original.transactionId.isNotBlank() &&
            scope(refund).isNotBlank() && scope(refund) == scope(original) &&
            ReceiptIdentity.normalize(refund.originalTransactionId) != ReceiptIdentity.normalize(original.transactionId) &&
            ReceiptIdentity.keys(original)[scope(refund)+"|payment"] != ReceiptIdentity.normalize(refund.originalTransactionId)) return true
        val originals = orderKeys(original)
        return orderKeys(refund).any { (key,id) -> originals[key]?.let { it != id } == true }
    }
    fun eligible(refund: BillInfoModel, candidate: Candidate): Boolean {
        val b = candidate.bill
        val refundUnits=runCatching { LedgerPolicy.minorUnits(refund.money) }.getOrNull() ?: return false
        val remainingUnits=runCatching { LedgerPolicy.minorUnits(candidate.remaining) }.getOrNull() ?: return false
        return refund.type == BillType.IncomeRefund && refund.eventStatus == "COMPLETED" &&
            b.id > 0 && b.id != refund.id && b.type == BillType.Expend && b.groupId == -1L &&
            b.state != BillState.Wait2Edit && !b.hasFlag(BillInfoModel.FLAG_NOT_COUNT) &&
            b.bookName == refund.bookName && b.currencyCode() == refund.currencyCode() &&
            remainingUnits >= refundUnits && b.time > 0 && refund.time > 0 &&
            b.time <= refund.time + 300_000 && !conflicts(refund,b) &&
            (exact(refund,b) || (originalAmount(refund)?.let { it == LedgerPolicy.minorUnits(b.money) } != false &&
                originalTime(refund)?.let { abs(it-b.time)<=300_000 } != false))
    }
    fun strong(refund: BillInfoModel, b: BillInfoModel): Boolean {
        val age = abs(refund.time - b.time)
        val sameShop = shop(refund).isNotEmpty() && shop(refund) == shop(b)
        val sameItem = item(refund).isNotEmpty() && item(refund) == item(b)
        val sameAccount = account(refund).isNotEmpty() && account(refund) == account(b)
        val sameOriginalPayment = originalAmount(refund)?.let { it == LedgerPolicy.minorUnits(b.money) } == true &&
            originalTime(refund)?.let { abs(it-b.time)<=60_000 } == true &&
            ((channel(refund).isNotEmpty() && channel(refund)==channel(b)) || sameAccount)
        return sameOriginalPayment || sameShop && (sameItem && age <= 30L*86400000 || sameAccount && age <= 86400000)
    }
    fun local(refund: BillInfoModel, candidates: List<Candidate>): BillInfoModel? {
        val valid = candidates.filter { eligible(refund,it) }
        val identities = valid.filter { exact(refund,it.bill) }
        if (identities.isNotEmpty()) return identities.singleOrNull()?.bill
        // Missing IDs on an original are not a contradiction; strong independent facts can still match.
        return valid.filter { strong(refund,it.bill) }.singleOrNull()?.bill
    }
    fun forAi(refund: BillInfoModel, candidates: List<Candidate>): List<Candidate> {
        if (candidates.count { eligible(refund,it) && exact(refund,it.bill) } > 1) return emptyList()
        fun overlaps(a:String,b:String)=a.length>=2 && b.length>=2 && (a.contains(b) || b.contains(a))
        fun relevance(b:BillInfoModel):Int {
            var score=0
            if (overlaps(shop(refund),shop(b))) score+=4
            if (overlaps(item(refund),item(b))) score+=4
            if (account(refund).isNotEmpty() && account(refund)==account(b)) score+=3
            if (channel(refund).isNotEmpty() && channel(refund)==channel(b)) score++
            if (originalAmount(refund)?.let { it==LedgerPolicy.minorUnits(b.money) } == true) score+=2
            if (originalTime(refund)?.let { abs(it-b.time)<=300_000 } == true) score+=2
            if (scope(refund).isNotBlank() && scope(refund)==scope(b)) score++
            return score
        }
        val plausible = candidates.filter { c ->
            eligible(refund,c) && abs(refund.time-c.bill.time) <= 90L*86400000 && relevance(c.bill)>=2
        }.sortedWith(compareByDescending<Candidate> { relevance(it.bill) }.thenBy { abs(refund.time-it.bill.time) })
        // Do not hide plausible alternatives by arbitrarily truncating the list.
        return if (plausible.size <= 8) plausible else emptyList()
    }
    fun indistinguishable(a: BillInfoModel, b: BillInfoModel) =
        clean(a.shopName) == clean(b.shopName) && clean(a.shopItem) == clean(b.shopItem) &&
            account(a) == account(b) && LedgerPolicy.minorUnits(a.money) == LedgerPolicy.minorUnits(b.money) &&
            abs(a.time-b.time) <= 300_000
}
