package org.ezbook.server.tools

import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A post-recognition decision, independent of payment-app layouts and pre-capture caches. */
object OcrRepeatPolicy {
    fun eligible(type: String, manual: Boolean, preview: Boolean) = type == "OCR" && !manual && !preview

    private fun normalized(value: String) = value.replace(Regex("\\s+"), "")
        .replace('（', '(').replace('）', ')').trim()

    fun samePayment(incoming: BillInfoModel, known: BillInfoModel, rawText: String): Boolean {
        if (ReceiptIdentity.ambiguous(incoming.app, rawText)) return false
        if (incoming.type != known.type || incoming.bookName != known.bookName ||
            incoming.currencyModel().code != known.currencyModel().code) return false
        val sameApp = incoming.app.isNotBlank() && incoming.app == known.app
        val sameNamespace = incoming.transactionNamespace.isNotBlank() && incoming.transactionNamespace == known.transactionNamespace
        val raw = normalized(rawText)
        val refund = incoming.type == BillType.IncomeRefund
        val incomingKeys = ReceiptIdentity.extract(incoming.app, rawText).filterKeys { it.endsWith("|refund") == refund }
        val knownKeys = ReceiptIdentity.keys(known).filterKeys { it.endsWith("|refund") == refund }
        val common = incomingKeys.keys.intersect(knownKeys.keys)
        // Conflicting evidence is not a reliable unique key, even if another field happens to match.
        if (common.any { incomingKeys[it] != knownKeys[it] }) return false
        if (common.any { key ->
            !key.endsWith("|merchant-order") || (incoming.shopName.isNotBlank() &&
                normalized(incoming.shopName) == normalized(known.shopName))
        }) return true
        if (!sameApp && !sameNamespace) return false
        val id = ReceiptIdentity.normalize(if (refund) incoming.refundId else incoming.transactionId)
        val priorId = ReceiptIdentity.normalize(if (refund) known.refundId else known.transactionId)
        // Compatibility for existing bills saved before typed identifiers were introduced.
        // A real unique identifier wins over an OCR list-price/paid-price disagreement.
        if ((incomingKeys.isEmpty() || knownKeys.isEmpty()) && id.isNotEmpty() && id == priorId &&
            ReceiptIdentity.grounded(id, rawText)) return true
        if (knownKeys.isEmpty() && ReceiptIdentity.grounded(priorId, rawText) &&
            incomingKeys.values.contains(priorId)) return true
        if (id.isNotEmpty() && priorId.isNotEmpty() && id != priorId && sameNamespace) return false
        val generic = setOf("", "微信", "支付宝", "银行卡", "零钱", "余额", "在线支付")
        if (incoming.accountNameFrom !in generic && known.accountNameFrom !in generic &&
            incoming.accountNameFrom != known.accountNameFrom) return false
        if (LedgerPolicy.minorUnits(incoming.money) != LedgerPolicy.minorUnits(known.money)) return false
        // A payment can have several partial refunds; payment time/details do not identify a refund.
        if (incoming.type == BillType.IncomeRefund || incoming.time <= 0 || incoming.time / 1000 != known.time / 1000) return false
        val merchant = normalized(incoming.shopName)
        val item = normalized(incoming.shopItem)
        if (merchant in setOf("", "美团", "微信", "微信支付", "支付宝", "未知商户")) return false
        if (merchant != normalized(known.shopName) || !raw.contains(merchant)) return false
        if (item.isNotEmpty() && known.shopItem.isNotBlank() && item != normalized(known.shopItem)) return false
        val sameItem = item.isNotEmpty() && item == normalized(known.shopItem) && raw.contains(item)
        val account = normalized(incoming.accountNameFrom)
        val sameAccount = account !in generic && account == normalized(known.accountNameFrom) && raw.contains(account)
        // Two composite keys: second + amount + merchant + item OR a specific payment account.
        if (!sameItem && !sameAccount) return false
        // Do not treat a model's default/current timestamp or minute-only date as identity.
        val expected = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(incoming.time))
        val dates = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?[ T]*(\\d{1,2})[:时](\\d{2})[:分](\\d{2})")
        return dates.findAll(raw).any { match ->
            match.groupValues.drop(1).mapIndexed { index, s -> if (index == 0) s else s.padStart(2, '0') }.joinToString("") == expected
        }
    }
}
