package org.ezbook.server.tools

import org.ezbook.server.constant.BillType
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel

object OccasionalLedger {
    /** Resolve outside the selected date range; returned copies do not rewrite saved bills. */
    suspend fun prepare(rows: List<BillInfoModel>): List<BillInfoModel> {
        val originals = rows.filter { it.type == BillType.IncomeRefund && it.originalBillId > 0 }
            .map { it.originalBillId }.distinct().associateWith { Db.get().billInfoDao().queryId(it) }
        return rows.map { OccasionalPolicy.withOriginal(it, originals[it.originalBillId]) }
    }
}
