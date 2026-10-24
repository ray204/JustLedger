package org.ezbook.server.tools

object ReceiptPolicy {
    fun matchesSnapshot(billJson: String, expected: String): Boolean =
        expected.isNotBlank() && MD5HashTable.md5(billJson) == expected

    fun shouldCreate(automatic: Boolean, saved: Boolean, child: Boolean, alreadyCreated: Boolean): Boolean =
        automatic && saved && !child && !alreadyCreated

    /** Rendering, timeouts, activity recreation and service shutdown are never consent. */
    fun acknowledge(previous: Long, explicitlyConfirmed: Boolean, now: Long): Long =
        if (previous == 0L && explicitlyConfirmed) now.coerceAtLeast(1) else previous
}
