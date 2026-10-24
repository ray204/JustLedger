package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class CapturePolicyTest {
    @Test fun differentAmountsAndOrderNumbersChangeOcrFingerprint() {
        assertNotEquals(CapturePolicy.contentFingerprint("支付成功 金额10.00 订单123"), CapturePolicy.contentFingerprint("支付成功 金额20.00 订单456"))
    }
    @Test fun contentBeyondThreeHundredCharactersStillDistinguishesTransactions() {
        val prefix = "商户说明".repeat(100)
        assertNotEquals(CapturePolicy.contentFingerprint(prefix + "订单123"), CapturePolicy.contentFingerprint(prefix + "订单456"))
    }
    @Test fun whitespaceOnlyChangesDoNotCreateNewOcrContent() {
        assertEquals(CapturePolicy.contentFingerprint("支付  10.00\n订单123"), CapturePolicy.contentFingerprint(" 支付 10.00 订单123 "))
    }
    @Test fun equalNotificationTextWithDifferentIdentityIsNotDropped() {
        val a = CapturePolicy.notificationEvent("n1", 100, "支付", "支出10元")
        assertNotEquals(a, CapturePolicy.notificationEvent("n2", 100, "支付", "支出10元"))
        assertNotEquals(a, CapturePolicy.notificationEvent("n1", 101, "支付", "支出10元"))
        assertEquals(a, CapturePolicy.notificationEvent("n1", 100, "支付", "支出10元"))
    }
    @Test fun refundIncomeIsNormalizedWithoutChangingOrdinaryIncome() {
        assertEquals("IncomeRefund", CapturePolicy.refundType("Income", "退款到账30元"))
        assertEquals("Income", CapturePolicy.refundType("Income", "工资到账100元"))
        assertEquals("Expend", CapturePolicy.refundType("Expend", "退款服务费支出1元"))
    }
    @Test fun unfinishedRefundsDoNotCountAsReceivedMoney() {
        listOf("退款申请已受理", "退款处理中", "退款失败", "退款成功，预计3天到账", "退款未到账").forEach {
            assertFalse(CapturePolicy.refundCompleted(it, "COMPLETED"))
        }
        assertFalse(CapturePolicy.refundCompleted("退款30元", "PENDING"))
        assertFalse(CapturePolicy.refundCompleted("退款成功", "FAILED"))
        assertTrue(CapturePolicy.refundCompleted("退款已到账30元", "COMPLETED"))
    }
}
