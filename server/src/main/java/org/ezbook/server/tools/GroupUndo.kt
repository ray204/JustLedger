package org.ezbook.server.tools

import androidx.room.withTransaction
import com.google.gson.Gson
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel

object GroupUndo {
    fun capture(child: BillInfoModel, parent: BillInfoModel) {
        child.mergeSnapshot = Gson().toJson(parent.copy(mergeSnapshot = "", ownSnapshot = ""))
        child.ownSnapshot = Gson().toJson(child.copy(mergeSnapshot = "", ownSnapshot = ""))
    }

    suspend fun undo(id: Long) = Db.get().withTransaction {
        val dao = Db.get().billInfoDao()
        val child = requireNotNull(dao.queryId(id)) { "账单不存在" }
        require(child.groupId != -1L) { "账单未合并" }
        require(child.mergeSnapshot.isNotBlank() && child.ownSnapshot.isNotBlank()) { "旧账单没有合并快照，请手动核对" }
        val parent = requireNotNull(dao.queryId(child.groupId)) { "原账单不存在" }
        require(dao.queryGroup(parent.id).none { it.id > child.id }) { "请先撤销最近一次合并" }
        require(child.mergedParentSnapshot == Gson().toJson(parent)) { "原账单在合并后已修改，请先人工核对，避免覆盖修改" }
        val originalParent = Gson().fromJson(child.mergeSnapshot, BillInfoModel::class.java)
        val originalChild = Gson().fromJson(child.ownSnapshot, BillInfoModel::class.java)
        originalChild.groupId = -1
        originalChild.reviewReason = "已撤销合并，请确认本笔账单"
        originalChild.setFlag(BillInfoModel.FLAG_NOT_COUNT, true)
        dao.update(originalParent)
        dao.update(originalChild)
        Db.get().billNoticeDao().refreshPending(originalParent.id, Gson().toJson(originalParent))
    }
}
