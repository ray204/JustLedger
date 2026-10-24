package org.ezbook.server.tools

import org.ezbook.server.constant.BillType
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel

object RefundManager {
    /** Editing an original payment must not invalidate completed, linked refunds. */
    suspend fun validateOriginalChanges(bill: BillInfoModel) {
        if (bill.id <= 0) return
        val dao = Db.get().billInfoDao()
        val refunded = dao.refundedAmount(bill.id, 0)
        if (refunded <= 0) return
        val previous = requireNotNull(dao.queryId(bill.id)) { "退款原单不存在" }
        require(bill.type == BillType.Expend && bill.groupId == -1L &&
            !bill.hasFlag(BillInfoModel.FLAG_NOT_COUNT) &&
            bill.state != org.ezbook.server.constant.BillState.Wait2Edit) { "此账单有关联退款，不能修改为非有效支出" }
        require(previous.bookName == bill.bookName && previous.currencyModel().code == bill.currencyModel().code) {
            "此账单有关联退款，不能单独修改账本或币种"
        }
        LedgerPolicy.validateRefund(bill.money, 0.0, refunded)
    }

    suspend fun resolve(bill: BillInfoModel) {
        if (bill.type != BillType.IncomeRefund) return
        if (bill.originalBillId == 0L && bill.originalTransactionId.isNotBlank()) {
            bill.originalBillId = Db.get().billInfoDao().byIdentity(bill.transactionNamespace, bill.originalTransactionId, "")
                .filter { it.type == BillType.Expend && it.bookName == bill.bookName && it.currencyModel().code == bill.currencyModel().code }
                .singleOrNull()?.id ?: 0
        }
        try {
            validate(bill)
        } catch (e: IllegalArgumentException) {
            bill.reviewReason = e.message ?: "请关联退款原账单"
            bill.auto = false
            bill.setFlag(BillInfoModel.FLAG_NOT_COUNT, true)
        }
    }

    suspend fun validate(bill: BillInfoModel) {
        if (bill.type != BillType.IncomeRefund) return
        require(bill.eventStatus == "COMPLETED") { "退款尚未到账，不能计入已完成账单" }
        val original = requireNotNull(Db.get().billInfoDao().queryId(bill.originalBillId)) { "请先选择本地退款原账单" }
        require(original.type == BillType.Expend && original.groupId == -1L && original.id != bill.id) { "退款原单必须为独立支出账单" }
        require(original.state != org.ezbook.server.constant.BillState.Wait2Edit && !original.hasFlag(BillInfoModel.FLAG_NOT_COUNT)) { "请先确认原支出账单" }
        require(original.bookName == bill.bookName && original.currencyModel().code == bill.currencyModel().code) { "退款与原单的账本或币种不一致" }
        LedgerPolicy.validateRefund(original.money, Db.get().billInfoDao().refundedAmount(original.id, bill.id), bill.money)
    }
}
