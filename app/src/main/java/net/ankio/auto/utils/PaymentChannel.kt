package net.ankio.auto.utils

import org.ezbook.server.db.model.BillInfoModel

/** Payment platform, distinct from the funding account (e.g. a card used in WeChat). */
object PaymentChannel {
    fun name(bill: BillInfoModel): String {
        val source = bill.app.lowercase()
        if (source == "com.tencent.mm") return "微信"
        if (source == "com.eg.android.alipaygphone") return "支付宝"
        val banks = mapOf("com.icbc" to "工商银行", "com.chinamworld" to "建设银行",
            "com.android.bankabc" to "农业银行", "com.chinamworld.bocmbci" to "中国银行",
            "cmb.pb" to "招商银行", "com.bankcomm" to "交通银行")
        banks[source]?.let { return it }
        val channel = bill.channel.trim()
        if (channel.contains("微信") || channel.contains("WeChat", true)) return "微信"
        if (channel.contains("支付宝") || channel.contains("Alipay", true)) return "支付宝"
        if (channel.isNotEmpty() && channel !in setOf("通知", "短信", "OCR", "ocr", "notification", "sms")) return channel
        return if (source == "net.ankio.auto" && !bill.auto) "手动" else "其他"
    }
}
