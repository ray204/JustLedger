package org.ezbook.server.tools

import com.google.gson.JsonObject
import org.ezbook.server.db.model.BillInfoModel

/** Independently scoped identifiers, derived from labelled source text, never from price. */
object ReceiptIdentity {
    const val FIELD = "_receiptKeysV1"
    private val labelled = Regex("(微信(?:支付)?(?:交易单号|交易号|支付单号)|支付宝(?:交易号|交易单号)|退款(?:单号|编号|流水号)|商户(?:订单号|单号)|订单(?:编号|号码|号)|支付(?:流水号|单号)|交易(?:流水号|单号|编号|号)|银行流水号)\\s*[:：]?\\s*([0-9]{3,6}(?:[ \\u00a0]+[0-9]{3,6}){1,7}|[A-Za-z0-9][A-Za-z0-9_-]{7,63})(?![A-Za-z0-9_-]|[ \\u00a0]+[0-9])")
    fun normalize(value: String) = value.replace(Regex("[\\s\\u00a0]+"), "")
    fun grounded(value: String, raw: String): Boolean = value.length in 8..64 &&
        Regex("(?<![A-Za-z0-9_-])${Regex.escape(value)}(?![A-Za-z0-9_-])").containsMatchIn(normalize(raw))

    private fun entries(app: String, raw: String): List<Pair<String, String>> {
        if (app.isBlank()) return emptyList()
        return labelled.findAll(raw).map { m ->
            val label = m.groupValues[1]
            val kind = when {
                label.startsWith("退款") -> "refund"
                label.startsWith("商户") -> "merchant-order"
                label.startsWith("订单") -> "order"
                label.startsWith("银行") -> "bank"
                else -> "payment"
            }
            // Only an explicit provider label or the provider's own app can bridge apps.
            val scope = when {
                kind == "payment" && (label.startsWith("微信") || app == "com.tencent.mm") -> "wechat"
                kind == "payment" && (label.startsWith("支付宝") || app == "com.eg.android.AlipayGphone") -> "alipay"
                else -> app
            }
            "$scope|$kind" to normalize(m.groupValues[2])
        }.filter { it.second.length in 8..64 }.toList()
    }

    fun ambiguous(app: String, raw: String) = entries(app, raw).groupBy({ it.first }, { it.second })
        .values.any { it.distinct().size > 1 }

    fun extract(app: String, raw: String): Map<String, String> {
        return entries(app, raw).groupBy({ it.first }, { it.second }).mapNotNull { (scope, ids) ->
            ids.distinct().singleOrNull()?.let { scope to it }
        }.toMap()
    }

    fun keys(bill: BillInfoModel): Map<String, String> {
        val obj = ReceiptDetails.read(bill.detailsJson).get(FIELD)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyMap()
        return obj.entrySet().mapNotNull { (key, value) ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) null
            else normalize(value.asString).takeIf { it.length in 8..64 }?.let { key to it }
        }.toMap()
    }

    fun attach(bill: BillInfoModel, raw: String) {
        val details = ReceiptDetails.read(bill.detailsJson)
        details.remove(FIELD) // Model output cannot manufacture internal evidence.
        val keys = extract(bill.app, raw)
        if (keys.isNotEmpty()) details.add(FIELD, JsonObject().apply { keys.forEach { (k,v) -> addProperty(k,v) } })
        bill.detailsJson = details.toString()
    }

    /** Learn other corroborated IDs after an unambiguous match; do not edit user bill fields. */
    fun remember(known: BillInfoModel, incoming: BillInfoModel): Boolean {
        val previous = keys(known)
        val added = keys(incoming).filter { (key, _) -> key !in previous }
        if (added.isEmpty()) return false
        val details = ReceiptDetails.read(known.detailsJson)
        details.add(FIELD, JsonObject().apply { (previous + added).forEach { (k,v) -> addProperty(k,v) } })
        known.detailsJson = details.toString()
        return true
    }
}
