package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class AutoOcrGateTest {
    @Test fun manualScanAndDialogDismissalDoNotRetriggerSameOrder() {
        val gate = AutoOcrGate()
        assertTrue(gate.begin("order-1", manual = true))
        val banner = Any(); val editor = Any(); val receipt = Any()
        gate.setPresenting(banner, true)
        gate.setPresenting(editor, true)
        gate.finish()
        assertFalse(gate.canAutomaticallyStart("order-1"))
        gate.setPresenting(banner, false)
        gate.setPresenting(receipt, true)
        gate.setPresenting(editor, false)
        assertTrue(gate.isBlocked())
        gate.setPresenting(receipt, false)
        // Repeated window changes never re-arm a timer for the already attempted content.
        repeat(20) { assertFalse(gate.begin("order-1", manual = false)) }
        assertTrue(gate.begin("order-1", manual = true))
    }

    @Test fun failedAttemptStaysConsumedButManualRetryAndNewOrderAreAllowed() {
        val gate = AutoOcrGate()
        assertTrue(gate.begin("failed-page", false))
        gate.finish() // Same cleanup path for failure, success and cancellation.
        assertFalse(gate.begin("failed-page", false))
        assertTrue(gate.begin("failed-page", true))
        gate.finish()
        assertTrue(gate.begin("different-order", false))
    }

    @Test fun busyAndNestedPresentersBlockBothPathsWithoutConsumingNextOrder() {
        val gate = AutoOcrGate()
        assertTrue(gate.begin("first", true))
        assertFalse(gate.begin("second", true))
        gate.finish()
        val a = Any(); val b = Any()
        gate.setPresenting(a, true); gate.setPresenting(a, true); gate.setPresenting(b, true)
        assertFalse(gate.begin("second", false))
        gate.setPresenting(a, false)
        assertFalse(gate.begin("second", true))
        gate.setPresenting(b, false)
        assertTrue(gate.begin("second", false))
    }

    @Test fun staleQueuedPageAndOwnOrMismatchedRootsCannotStartAutomaticCapture() {
        val gate = AutoOcrGate()
        assertFalse(gate.begin("new-page", false, "old-page"))
        assertTrue(gate.canAutomaticallyStart("new-page"))
        assertFalse(gate.begin(null, false))
        assertFalse(gate.begin("", false))
        assertFalse(AutoOcrGate.isExternalSource("payment.app", "our.app", "our.app"))
        assertFalse(AutoOcrGate.isExternalSource("our.app", "payment.app", "our.app"))
        assertFalse(AutoOcrGate.isExternalSource("old.app", "new.app", "our.app"))
        assertFalse(AutoOcrGate.isExternalSource(null, null, "our.app"))
        assertTrue(AutoOcrGate.isExternalSource("payment.app", "payment.app", "our.app"))
    }

    @Test fun boundedHistoryKeepsRecentAttemptsAndManualScanWithoutTreeWorks() {
        val gate = AutoOcrGate(2)
        for (key in listOf("one", "two", "three")) { assertTrue(gate.begin(key, false)); gate.finish() }
        assertFalse(gate.canAutomaticallyStart("two"))
        assertFalse(gate.canAutomaticallyStart("three"))
        assertTrue(gate.canAutomaticallyStart("one"))
        assertTrue(gate.begin(null, true))
        gate.finish()
        assertThrows(IllegalArgumentException::class.java) { AutoOcrGate(0) }
    }
}
