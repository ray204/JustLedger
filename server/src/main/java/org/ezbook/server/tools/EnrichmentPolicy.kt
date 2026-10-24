package org.ezbook.server.tools

import org.ezbook.server.db.model.BillInfoModel

/** Only fill missing data or machine-generated placeholders. Never change ledger money/time/type. */
object EnrichmentPolicy {
    private val genericShops = setOf("", "美团", "微信", "微信支付", "支付宝", "未知商户", "未提供商户")
    fun compatible(source: BillInfoModel, target: BillInfoModel): Boolean {
        if (source.id == target.id || source.type != target.type || source.bookName != target.bookName ||
            source.currencyModel().code != target.currencyModel().code ||
            LedgerPolicy.minorUnits(source.money) != LedgerPolicy.minorUnits(target.money)) return false
        return source.transactionNamespace != target.transactionNamespace || source.transactionId.isBlank() ||
            target.transactionId.isBlank() || source.transactionId == target.transactionId
    }

    fun enrich(source: BillInfoModel, target: BillInfoModel): Boolean {
        require(compatible(source, target)) { "金额、币种或交易凭据不一致，不能补充此账单" }
        return fillMissing(source, target)
    }

    /** Caller has already resolved a grounded unique identity, including price-reading errors. */
    fun enrichRecognized(source: BillInfoModel, target: BillInfoModel): Boolean {
        require(source.type == target.type && source.bookName == target.bookName &&
            source.currencyModel().code == target.currencyModel().code)
        return fillMissing(source, target)
    }

    private fun fillMissing(source: BillInfoModel, target: BillInfoModel): Boolean {
        val before = target.copy()
        target.detailsJson = ReceiptDetails.merge(target.detailsJson, source.detailsJson)
        if (target.shopName.isBlank() || target.auto && target.shopName in genericShops)
            source.shopName.takeIf { it !in genericShops }?.let { target.shopName = it }
        if (target.shopItem.isBlank()) target.shopItem = source.shopItem
        if (target.accountNameFrom.isBlank()) target.accountNameFrom = source.accountNameFrom
        if (target.accountNameTo.isBlank()) target.accountNameTo = source.accountNameTo
        if (target.channel.isBlank()) target.channel = source.channel
        else if (target.auto && target.channel == "美团" && source.channel in setOf("微信", "支付宝")) target.channel = source.channel
        if (target.cateName.isBlank() || target.auto && target.cateName in setOf("其他", "其它")) {
            if (source.hasValidCategory()) target.cateName = source.cateName
        }
        if (target.remark.isBlank() || target.auto && target.remark == before.shopName) {
            target.remark = listOf(target.shopName, target.shopItem).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        }
        if (target.transactionId.isBlank() && source.transactionId.isNotBlank()) {
            target.transactionId = source.transactionId
            target.transactionNamespace = source.transactionNamespace
        }
        return target != before
    }
}
