package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class ReceiptPolicyTest {
    @Test fun updatedBillCannotBeAcknowledgedUsingAnOldReceipt() {
        val displayed = "{\"money\":20}"
        val updated = "{\"money\":30}"
        val snapshot = MD5HashTable.md5(displayed)
        assertTrue(ReceiptPolicy.matchesSnapshot(displayed, snapshot))
        assertFalse(ReceiptPolicy.matchesSnapshot(updated, snapshot))
        assertFalse(ReceiptPolicy.matchesSnapshot(displayed, ""))
    }
    @Test fun automaticCommittedBillCreatesOneReceipt() {
        assertTrue(ReceiptPolicy.shouldCreate(true, true, false, false))
        assertFalse(ReceiptPolicy.shouldCreate(true, true, false, true))
    }
    @Test fun failedPendingManualOrGroupedBillsNeverClaimAutomaticSuccess() {
        assertFalse(ReceiptPolicy.shouldCreate(true, false, false, false))
        assertFalse(ReceiptPolicy.shouldCreate(false, true, false, false))
        assertFalse(ReceiptPolicy.shouldCreate(true, true, true, false))
    }
    @Test fun timePassingAndRestartDoNotAcknowledgeReceipt() {
        var acknowledgedAt = 0L
        listOf(100L, 10000L, 86400000L).forEach { time ->
            acknowledgedAt = ReceiptPolicy.acknowledge(acknowledgedAt, false, time)
            assertEquals(0L, acknowledgedAt)
        }
    }
    @Test fun explicitConfirmationIsIdempotent() {
        val first = ReceiptPolicy.acknowledge(0, true, 100)
        assertEquals(100L, first)
        assertEquals(first, ReceiptPolicy.acknowledge(first, true, 200))
        assertEquals(first, ReceiptPolicy.acknowledge(first, false, 300))
    }
}
