package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class LedgerPolicyTest {
    private val payment = LedgerPolicy.Evidence(100.0, "CNY", "日常", "Expend", "wechat", "pay-1", account = "招行1234")
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid ledger operation to be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun equalPaymentsWithoutIdentityRemainIndependent() {
        assertFalse(LedgerPolicy.sameTransaction(payment.copy(transactionId = ""), payment.copy(transactionId = "")))
    }
    @Test fun distinctPaymentIdsNeverMerge() {
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(transactionId = "pay-2")))
    }
    @Test fun delayedSamePaymentWithGenericChannelAccountMerges() {
        assertTrue(LedgerPolicy.sameTransaction(payment, payment.copy(account = "微信")))
    }
    @Test fun matchingIdsCannotCrossCurrencyBookOrProvider() {
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(currency = "USD")))
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(book = "工作")))
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(namespace = "alipay")))
    }
    @Test fun conflictingAmountsOrCardsNeedReview() {
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(amount = 101.0)))
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(account = "招行5678")))
    }
    @Test fun equalPartialRefundsRequireTheirOwnIdentity() {
        val refund = payment.copy(type = "IncomeRefund", amount = 30.0)
        assertFalse(LedgerPolicy.sameTransaction(refund, refund))
        assertFalse(LedgerPolicy.sameTransaction(refund.copy(refundId = "refund-1"), refund.copy(refundId = "refund-2")))
        assertTrue(LedgerPolicy.sameTransaction(refund.copy(refundId = "refund-1"), refund.copy(refundId = "refund-1")))
    }
    @Test fun paymentAndRefundAreDistinctEvents() {
        assertFalse(LedgerPolicy.sameTransaction(payment, payment.copy(type = "IncomeRefund", refundId = "refund-1")))
    }
    @Test fun exactSourceReplayCanMatchWithoutAnOrderId() {
        val source = payment.copy(transactionId = "", sourceKey = "sms-event-1")
        assertTrue(LedgerPolicy.sameTransaction(source, source.copy()))
        assertFalse(LedgerPolicy.sameTransaction(source, source.copy(sourceKey = "sms-event-2")))
    }
    @Test fun unrelatedEqualIncomeAndExpenseAreNotTransfers() {
        assertFalse(LedgerPolicy.canTransfer(payment, payment.copy(type = "Income", transactionId = "pay-2"), "招行1234", "建行5678", setOf("招行1234", "建行5678")))
    }
    @Test fun identifiedTransferRequiresTwoDistinctOwnedAccounts() {
        val incoming = payment.copy(type = "Income", account = "建行5678")
        assertTrue(LedgerPolicy.canTransfer(payment, incoming, "招行1234", "建行5678", setOf("招行1234", "建行5678")))
        assertFalse(LedgerPolicy.canTransfer(payment, incoming, "招行1234", "陌生人", setOf("招行1234")))
        assertFalse(LedgerPolicy.canTransfer(payment, incoming, "招行1234", "招行1234", setOf("招行1234")))
    }
    @Test fun refundsNeverBecomeTransfers() {
        assertFalse(LedgerPolicy.canTransfer(payment, payment.copy(type = "IncomeRefund"), "招行1234", "建行5678", setOf("招行1234", "建行5678")))
    }
    @Test fun transferCurrencyAndBookMustMatch() {
        val incoming = payment.copy(type = "Income")
        assertFalse(LedgerPolicy.canTransfer(payment, incoming.copy(currency = "USD"), "A", "B", setOf("A", "B")))
        assertFalse(LedgerPolicy.canTransfer(payment, incoming.copy(book = "other"), "A", "B", setOf("A", "B")))
    }
    @Test fun partialRefundsMayExactlyExhaustOriginal() {
        LedgerPolicy.validateRefund(100.0, 0.0, 30.0)
        LedgerPolicy.validateRefund(100.0, 30.0, 70.0)
    }
    @Test fun overRefundAndAlreadyFullyRefundedAreRejected() {
        rejects { LedgerPolicy.validateRefund(100.0, 30.0, 70.01) }
        rejects { LedgerPolicy.validateRefund(100.0, 100.0, 0.01) }
        rejects { LedgerPolicy.validateRefund(100.0, 101.0, 1.0) }
    }
    @Test fun decimalRefundBoundaryUsesMinorUnits() {
        LedgerPolicy.validateRefund(0.3, 0.1, 0.2)
        assertEquals(101L, LedgerPolicy.minorUnits(1.005))
    }
    @Test fun zeroNegativeNonFiniteAndSubCentAmountsAreRejected() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.001).forEach { amount ->
            rejects { LedgerPolicy.minorUnits(amount) }
        }
        rejects { LedgerPolicy.validateRefund(100.0, -1.0, 1.0) }
    }
}
