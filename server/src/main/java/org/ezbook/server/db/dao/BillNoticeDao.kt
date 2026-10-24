package org.ezbook.server.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ezbook.server.db.model.BillNoticeModel

@Dao
interface BillNoticeDao {
    @Query("DELETE FROM BillNoticeModel WHERE billId = :billId")
    suspend fun deleteForBill(billId: Long): Int
    @Query("SELECT * FROM BillNoticeModel WHERE id = :id")
    suspend fun get(id: Long): BillNoticeModel?
    @Query("UPDATE BillNoticeModel SET billJson = :json WHERE billId = :billId AND acknowledgedAt = 0")
    suspend fun refreshPending(billId: Long, json: String): Int
    @Insert suspend fun insert(notice: BillNoticeModel): Long
    @Query("SELECT * FROM BillNoticeModel WHERE acknowledgedAt = 0 ORDER BY id ASC")
    suspend fun pending(): List<BillNoticeModel>
    @Query("SELECT COUNT(*) FROM BillNoticeModel WHERE billId = :billId AND kind = :kind")
    suspend fun count(billId: Long, kind: String): Int
    @Query("UPDATE BillNoticeModel SET acknowledgedAt = :time WHERE id = :id AND acknowledgedAt = 0")
    suspend fun acknowledge(id: Long, time: Long): Int
}
