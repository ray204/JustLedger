package org.ezbook.server.ai.tools

import com.google.gson.*
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.tools.DateUtils

/** Parse only a single receipt object; model text cannot set DB ids, flags or merge decisions. */
object ReceiptResponseParser {
    private val known = setOf("pageType","money","type","currency","fee","shopName","shopItem","accountNameFrom","accountNameTo",
        "cateName","timeText","channel","transactionId","transactionNamespace","refundId","originalTransactionId","eventStatus","extra")
    private val reserved = setOf("id","groupId","state","auto","flag","sourceKey","reviewReason","bookName","time",
        "originalBillId","mergeSnapshot","ownSnapshot","mergedParentSnapshot","detailsJson","ruleName")

    fun parse(response: String, requireReceiptPage: Boolean = false): BillInfoModel? {
        require(response.length <= 32000) { "输出过长" }
        val text = response.replace(Regex("(?is)<think\\b[^>]*>.*?</think>"), "").trim()
        // Locate balanced objects, respecting quoted braces/escapes. Supports prose and ```json fences.
        val objects = mutableListOf<JsonObject>()
        var start=-1; var depth=0; var quoted=false; var escaped=false
        for ((i,c) in text.withIndex()) {
            if (start < 0) { if (c=='{') { start=i; depth=1 }; continue }
            if (quoted) {
                if (escaped) escaped=false else if (c=='\\') escaped=true else if (c=='"') quoted=false
            } else when(c) {
                '"' -> quoted=true
                '{' -> depth++
                '}' -> { depth--; if (depth==0) {
                    objects.add(JsonParser.parseString(text.substring(start,i+1)).asJsonObject); start=-1
                } }
            }
        }
        require(start == -1 && objects.size == 1) { "请返回一个完整的账单对象" }
        val obj=objects.single()
        if (obj.size()==0) return null
        val page = obj.get("pageType")
        if (requireReceiptPage || page != null) {
            require(page != null && page.isJsonPrimitive && page.asJsonPrimitive.isString &&
                page.asString in setOf("receipt", "other")) { "pageType须为receipt或other，先判断是否为单笔交易详情" }
            if (page.asString == "other") return null
        }
        fun string(key:String, limit:Int=500):String {
            val value=obj.get(key) ?: return ""
            if (value.isJsonNull) return ""
            require(value.isJsonPrimitive) { "$key 应是文本" }
            return value.asString.take(limit)
        }
        val money = obj.get("money")?.asDouble ?: error("缺少实付金额 money")
        require(money.isFinite() && money > 0 && money < 1e9) { "money 应是正数实付金额" }
        val type = runCatching { BillType.valueOf(string("type")) }.getOrNull()
        require(type in setOf(BillType.Expend,BillType.Income,BillType.Transfer,BillType.IncomeRefund)) { "type 无效" }
        val currency=string("currency").ifBlank { "CNY" }.uppercase()
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency 应是三位币种代码" }
        val status=string("eventStatus").ifBlank { "COMPLETED" }
        if (status != "COMPLETED") return null
        val extra = obj.get("extra")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonObject) { "extra 应是对象" }; it.asJsonObject.deepCopy()
        } ?: JsonObject()
        for ((key,value) in obj.entrySet()) if (key !in known && key !in reserved) extra.add(key,value.deepCopy())
        require(extra.toString().length <= 8000 && extra.size() <= 40) { "扩展信息过长" }
        val fee = obj.get("fee")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
        require(fee.isFinite() && fee >= 0) { "fee 无效" }
        return BillInfoModel().apply {
            this.money=money; this.type=requireNotNull(type); this.currency=currency; this.fee=fee
            shopName=string("shopName"); shopItem=string("shopItem",1500)
            accountNameFrom=string("accountNameFrom"); accountNameTo=string("accountNameTo")
            cateName=string("cateName"); channel=string("channel")
            transactionId=string("transactionId",100); transactionNamespace=string("transactionNamespace",100)
            refundId=string("refundId",100); originalTransactionId=string("originalTransactionId",100)
            eventStatus=status; detailsJson=extra.toString()
            time=string("timeText").takeIf { it.isNotBlank() }?.let { runCatching { DateUtils.toEpochMillis(it) }.getOrNull() }
                ?: System.currentTimeMillis()
        }
    }
}
