package org.ezbook.server.tools

import org.ezbook.server.constant.BillType
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel

object TransferRecognizer {
    suspend fun recognize(bill: BillInfoModel): BillInfoModel? {
        if (!SettingUtils.featureAssetManager() || !SettingUtils.autoTransferRecognition()) return null
        if (bill.type !in setOf(BillType.Income, BillType.Expend, BillType.Transfer)) return null
        val owned = Db.get().assetsDao().load().map { it.name }.toSet()
        val matches = Db.get().billInfoDao().byIdentity(bill.transactionNamespace, bill.transactionId, "")
            .filter { other ->
                if (other.id == bill.id || other.type == bill.type && bill.type != BillType.Transfer) return@filter false
                val (from, to) = accounts(bill, other)
                LedgerPolicy.canTransfer(bill.evidence(), other.evidence(), from, to, owned)
            }
        return matches.singleOrNull()
    }

    private fun accounts(a: BillInfoModel, b: BillInfoModel): Pair<String, String> {
        val explicit = listOf(a, b).firstOrNull { it.type == BillType.Transfer }
        if (explicit != null) return explicit.accountNameFrom to explicit.accountNameTo
        return listOf(a, b).firstOrNull { it.type == BillType.Expend }?.accountNameFrom.orEmpty() to
            listOf(a, b).firstOrNull { it.type == BillType.Income }?.accountNameFrom.orEmpty()
    }

    suspend fun process(current: BillInfoModel, parent: BillInfoModel) {
        GroupUndo.capture(current, parent)
        val (from, to) = accounts(current, parent)
        parent.type = BillType.Transfer
        parent.accountNameFrom = from
        parent.accountNameTo = to
        BillMerger.mergeShopInfo(current, parent)
        BillMerger.mergeChannelInfo(current, parent)
        current.groupId = parent.id
        parent.cateName = ""
        BillMerger.saveBillGroup(current, parent)
    }
}
