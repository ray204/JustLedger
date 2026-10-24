package org.ezbook.server.tools

import org.ezbook.server.db.model.BillInfoModel

fun BillInfoModel.evidence() = LedgerPolicy.Evidence(
    money, currencyModel().code.ifBlank { "CNY" }, bookName, type.name,
    transactionNamespace, transactionId, refundId, sourceKey, accountNameFrom
)
