package org.ezbook.server.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable receipt. Displaying/dismissing a window never acknowledges it. */
@Entity
data class BillNoticeModel(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var billId: Long = 0,
    var billJson: String = "",
    var createdAt: Long = 0,
    var acknowledgedAt: Long = 0,
    var kind: String = "AUTO_SAVED"
)
