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

    /** Read-only suggestion; callers keep manual selection editable and validate again on save. */
    suspend fun suggest(bill: BillInfoModel): BillInfoModel? {
        if (bill.type != BillType.IncomeRefund || bill.eventStatus != "COMPLETED") return null
        val dao = Db.get().billInfoDao()
        if (bill.originalBillId > 0) return runCatchingExceptCancel { validate(bill); dao.queryId(bill.originalBillId) }.getOrNull()
        val payments = dao.refundOriginals().toMutableList()
        if (bill.originalTransactionId.isNotBlank()) payments.addAll(dao.ocrIdentityCandidates(bill.bookName,bill.originalTransactionId))
        ReceiptIdentity.keys(bill).filterKeys { it.endsWith("|order") || it.endsWith("|merchant-order") }
            .values.distinct().forEach { payments.addAll(dao.ocrIdentityCandidates(bill.bookName,it)) }
        val candidates = payments.distinctBy { it.id }.filter { it.type == BillType.Expend }.map {
            RefundMatchPolicy.Candidate(it,it.money-dao.refundedAmount(it.id,bill.id))
        }
        val local=RefundMatchPolicy.local(bill,candidates)
        if (local != null) return local
        if (!SettingUtils.featureAiAvailable() || !SettingUtils.aiBillRecognition() ||
            SettingUtils.apiKey("").isBlank() || SettingUtils.apiUri("").isBlank() || SettingUtils.apiModel("").isBlank()) return null
        val limited=RefundMatchPolicy.forAi(bill,candidates)
        val selected=runCatchingExceptCancel {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                org.ezbook.server.ai.tools.RefundLinkRecognition.pick(bill,limited) { input ->
                    org.ezbook.server.ai.AiManager.getInstance().request(
                        org.ezbook.server.ai.tools.RefundLinkRecognition.PROMPT,input).getOrThrow()
                }
            }
        }.getOrNull() ?: return null
        // The network call may have raced another refund or a user editing the original.
        return runCatchingExceptCancel {
            val current=requireNotNull(dao.queryId(selected.id))
            val remaining=current.money-dao.refundedAmount(current.id,bill.id)
            require(RefundMatchPolicy.eligible(bill,RefundMatchPolicy.Candidate(current,remaining)))
            validate(bill.copy(originalBillId=current.id));current
        }.getOrNull()
    }

    suspend fun resolve(bill: BillInfoModel) {
        if (bill.type != BillType.IncomeRefund) return
        // This runs inside the save transaction: only revalidate, never call a model here.
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
