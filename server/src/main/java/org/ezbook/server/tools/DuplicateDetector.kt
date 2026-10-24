package org.ezbook.server.tools

import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel

object DuplicateDetector {
    suspend fun detect(bill: BillInfoModel): BillInfoModel? {
        if (!SettingUtils.autoGroup()) return null
        val dao = Db.get().billInfoDao()
        val identified = dao.byIdentityIncludingAliases(bill.transactionNamespace, bill.transactionId, bill.refundId)
            .filter { it.id != bill.id && if (bill.type == org.ezbook.server.constant.BillType.IncomeRefund)
                bill.refundId.isNotBlank() && it.refundId == bill.refundId
                else it.type != org.ezbook.server.constant.BillType.IncomeRefund }
        val identityMatches = identified.filter { LedgerPolicy.sameTransaction(bill.evidence(), it.evidence()) }
            .mapNotNull { if (it.groupId == -1L) it else dao.queryId(it.groupId) }
            .filter { EnrichmentPolicy.compatible(bill, it) }.distinctBy { it.id }
        // Ambiguity must never silently select the oldest candidate.
        if (identityMatches.size == 1 && !bill.generateByAi()) {
            val parent = identityMatches.single()
            GroupUndo.capture(bill, parent)
            bill.groupId = parent.id
            EnrichmentPolicy.enrich(bill, parent)
            BillMerger.saveBillGroup(bill, parent)
            return parent
        }
        val window = SettingUtils.autoGroupTimeThreshold().coerceAtLeast(0) * 1000L
        val nearby = dao.query(bill.money, bill.time - window, bill.time + window, bill.type)
            .filter { it.id != bill.id && it.bookName == bill.bookName && it.currencyModel().code == bill.currencyModel().code }
        val plausible = nearby.filterNot { it.transactionNamespace == bill.transactionNamespace &&
            it.transactionId.isNotBlank() && bill.transactionId.isNotBlank() && it.transactionId != bill.transactionId }
        if (plausible.isNotEmpty() || identified.isNotEmpty()) {
            bill.reviewReason = "存在同额账单，缺少唯一交易凭据，请确认是否为独立交易"
            bill.auto = false
            bill.setFlag(BillInfoModel.FLAG_NOT_COUNT, true)
        }
        return null
    }
}
