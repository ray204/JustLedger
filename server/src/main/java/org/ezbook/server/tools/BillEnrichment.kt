package org.ezbook.server.tools

import androidx.room.withTransaction
import com.google.gson.Gson
import org.ezbook.server.constant.BillState
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.db.model.BillNoticeModel

object BillEnrichment {
    suspend fun candidates(id: Long): List<BillInfoModel> {
        val source = requireNotNull(Db.get().billInfoDao().queryId(id)) { "待补充信息不存在" }
        if (source.groupId != -1L) return emptyList()
        return Db.get().billInfoDao().enrichmentCandidates(source.money)
            .filter { PossibleDuplicatePolicy.plausible(source, it) }
            .sortedBy { kotlin.math.abs(source.time - it.time) }.take(5)
    }

    suspend fun confirm(sourceId: Long, targetId: Long, sourceSnapshot: String, targetSnapshot: String): BillInfoModel = Db.get().withTransaction {
        val dao = Db.get().billInfoDao()
        val source = requireNotNull(dao.queryId(sourceId)) { "识别记录不存在" }
        val target = requireNotNull(dao.queryId(targetId)) { "原账单不存在" }
        // A retry after a committed merge must not create another update or receipt.
        if (source.groupId == target.id) return@withTransaction target
        require(source.groupId == -1L && target.groupId == -1L && source.id != target.id) { "账单关联已变化，请重新选择" }
        require(source.state == BillState.Wait2Edit && source.hasFlag(BillInfoModel.FLAG_NOT_COUNT)) { "仅可将待核对信息补充到账单" }
        require(target.state != BillState.Wait2Edit && !target.hasFlag(BillInfoModel.FLAG_NOT_COUNT)) { "请先确认原账单" }
        require(MD5HashTable.md5(Gson().toJson(source)) == sourceSnapshot &&
            MD5HashTable.md5(Gson().toJson(target)) == targetSnapshot) { "账单已修改，请重新选择" }
        require(EnrichmentPolicy.compatible(source, target)) { "交易信息不一致，不能关联" }
        GroupUndo.capture(source, target)
        val changed = EnrichmentPolicy.enrich(source, target)
        source.groupId = target.id
        source.state = BillState.Edited
        source.mergedParentSnapshot = Gson().toJson(target)
        dao.update(target)
        dao.update(source)
        if (changed) {
            val notices = Db.get().billNoticeDao()
            val json = Gson().toJson(target)
            if (notices.refreshPending(target.id, json) == 0) notices.insert(BillNoticeModel(
                billId = target.id, billJson = json, createdAt = System.currentTimeMillis(), kind = "UPDATED"))
        }
        target
    }
}
