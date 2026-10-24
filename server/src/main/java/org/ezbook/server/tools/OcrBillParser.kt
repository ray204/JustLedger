package org.ezbook.server.tools

/** Local page formats. Every new OCR bill is reviewed; OCR text never proves a new payment. */
object OcrBillParser {
    data class Draft(
        val money: Double, val shopName: String, val shopItem: String = "",
        val channel: String, val accountNameFrom: String = "",
        val transactionId: String = "", val transactionNamespace: String,
        val timeText: String = "", val type: String = "Expend", val currency: String = "CNY",
        val cateName: String = "", val extra: Map<String,String> = emptyMap()
    )
    private val number = "(?:0|[1-9]\\d{0,8})(?:\\.\\d{1,2})?"
    private val currencyAmount = Regex("[¥￥]\\s*($number)(?![\\d.,])")
    private val blocked = Regex("待付款|等待付款|立即支付|确认支付|支付失败|交易关闭|已取消|退款|退回|转账|收款到账")
    private val amountLabel = Regex("^(实付|实付款|实付金额|支付金额|付款金额|合计)[：:]?")

    fun parse(app: String, text: String): Draft? {
        if (OcrPagePolicy.isNonTransaction(app, text)) return null
        if (blocked.containsMatchIn(text) || Regex("USD|HKD|EUR|JPY|美元|港币|日元|欧元|\\$").containsMatchIn(text)) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        fun field(vararg names: String): String {
            for ((i, line) in lines.withIndex()) for (name in names.sortedByDescending { it.length }) {
                if (line == name || line == "$name：" || line == "$name:") return lines.getOrNull(i + 1).orEmpty()
                if (line.startsWith("$name：") || line.startsWith("$name:")) return line.substring(name.length + 1).trim()
                if (line.startsWith(name) && line.length > name.length) return line.substring(name.length).trim()
            }
            return ""
        }
        fun identifier(vararg names: String): String = field(*names).takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) }.orEmpty()
        fun labelledAmount(): Double? {
            val values = lines.flatMapIndexed { i, line ->
                if (!amountLabel.containsMatchIn(line)) emptyList()
                else currencyAmount.findAll(line + " " + lines.getOrNull(i + 1).orEmpty()).map { it.groupValues[1].toDouble() }.toList()
            }.distinct()
            return values.singleOrNull()?.takeIf { it > 0 }
        }
        val date = field("交易时间", "支付时间", "付款时间").takeIf {
            it.matches(Regex("\\d{4}[-/]\\d{2}[-/]\\d{2} \\d{2}:\\d{2}(:\\d{2})?"))
        }.orEmpty()
        return when (app) {
            "com.sankuai.meituan", "com.sankuai.waimai" -> {
                if (!Regex("订单信息|订单详情|订单号码").containsMatchIn(text)) return null
                if (!Regex("支付成功|已支付|付款成功|取餐码|已完成|配送中|商家已接单").containsMatchIn(text)) return null
                val amount = labelledAmount() ?: return null
                val merchantIndex = lines.indexOfFirst { it.contains(Regex("[（(].*店[）)]")) }
                val merchant = field("商家名称", "商户名称").ifBlank { lines.getOrNull(merchantIndex).orEmpty() }.trimEnd('>', '＞', ' ')
                if (merchant.isBlank()) return null
                val item = field("商品名称").ifBlank {
                    if (merchantIndex < 0) "" else lines.drop(merchantIndex + 1).take(6)
                        .firstOrNull { !currencyAmount.containsMatchIn(it) && it.length > 2 &&
                            !Regex("进商家|粉丝群|关注|用户配送|免配送|已优惠|已用|默认|商品费用").containsMatchIn(it) }.orEmpty()
                }
                val payment=field("支付方式").takeUnless { it.contains("等") || it.contains("/") }.orEmpty()
                val details=linkedMapOf<String,String>()
                Regex("已优惠([0-9]+(?:\\.[0-9]{1,2})?)元").find(text)?.let { details["优惠金额"]=it.groupValues[1]+" 元" }
                lines.firstNotNullOfOrNull { Regex("^[Xx×]([0-9]+)$").find(it) }?.let { details["商品数量"]=it.groupValues[1] }
                if (payment.isNotBlank()) details["支付说明"]=payment
                Draft(amount, merchant, item, "美团", payment.takeUnless { it=="在线支付" }.orEmpty(),
                    identifier("订单号码", "订单编号", "订单号"), "meituan:order", date, extra=details)
            }
            "com.tencent.mm" -> {
                if (!Regex("支付成功|付款成功|交易成功|已支付").containsMatchIn(text)) return null
                val amount = if (lines.any { amountLabel.containsMatchIn(it) }) labelledAmount() else
                    lines.filter { it.matches(Regex("(?:-?[¥￥]\\s*|-)${number}")) }
                        .mapNotNull { it.replace(Regex("[-¥￥\\s]"), "").toDoubleOrNull() }.distinct().singleOrNull()?.takeIf { it > 0 }
                if (amount == null) return null
                val merchant = field("收款方", "商户名称", "商家", "收款商户")
                if (merchant.isBlank()) return null
                Draft(amount, merchant, field("商品", "商品说明", "商品名称"), "微信",
                    field("支付方式", "付款方式"), identifier("交易单号", "微信支付订单号"), "wechat:transaction", date)
            }
            else -> null
        }
    }
}
