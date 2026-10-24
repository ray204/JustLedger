package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class RecognizedOrderPagesTest {
    private val stored = mutableSetOf<String>()
    private fun history() = RecognizedOrderPages(stored::contains) { stored.add(it) }

    @Test fun sameOrderSurvivesRestartLayoutAndStatusChangesButManualCanRetry() {
        history().remember("payment.app", "订单号码 1234567890123456789\n已付款 12.00 等待取餐")
        val reopened = history()
        val changed = "订单已完成\n商品 咖啡\n订单编号：1234567890123456789\n再来一单 16:30"
        assertTrue(reopened.shouldSkip("payment.app", changed))
        assertFalse(reopened.shouldSkip("payment.app", changed, manual = true))
        assertTrue(stored.all { it.matches(Regex("[a-f0-9]{64}")) })
    }

    @Test fun sameAmountDifferentOrdersAppsAndIdKindsAreNeverSuppressed() {
        val h = history()
        h.remember("payment.app", "订单号：12345678 金额12.00 商户咖啡")
        assertFalse(h.shouldSkip("payment.app", "订单号：12345679 金额12.00 商户咖啡"))
        assertFalse(h.shouldSkip("another.app", "订单号：12345678 金额12.00 商户咖啡"))
        assertFalse(h.shouldSkip("payment.app", "交易单号：12345678 金额12.00 商户咖啡"))
        assertFalse(h.shouldSkip("payment.app", "订单号：12345678A 金额12.00 商户咖啡"))
    }

    @Test fun missingOrAmbiguousIdentityUsesContentOnlyWithoutGuessing() {
        val h = history()
        h.remember("app", "咖啡 12.00 取餐码123")
        assertTrue(h.shouldSkip("app", "  咖啡\n12.00   取餐码123  "))
        assertFalse(h.shouldSkip("app", "咖啡 12.00 取餐码124"))
        h.remember("app", "订单号12345678 订单号87654321")
        assertFalse(h.shouldSkip("app", "订单号12345678 单独一笔"))
        assertFalse(h.shouldSkip("app", "订单号87654321 单独一笔"))
        assertEquals(1, RecognizedOrderPages.keys("app", "订单号1234567").size)
        assertEquals(2, RecognizedOrderPages.keys("app", "订单号12345678").size)
        assertEquals(2, RecognizedOrderPages.keys("app", "订单号" + "1".repeat(64)).size)
        assertEquals(1, RecognizedOrderPages.keys("app", "订单号" + "1".repeat(65)).size)
        assertTrue(RecognizedOrderPages.keys("app", " ").isEmpty())
        assertTrue(RecognizedOrderPages.keys("", "订单号12345678").isEmpty())
    }

    @Test fun observedOrderAndTransactionAliasesBothSuppressRepeatWithoutStoringRawText() {
        val h = history()
        h.remember("app", "订单号ABCDEFGH-123 交易单号987654321 商户单号SHOP12345")
        for (text in listOf("订单编号：ABCDEFGH-123", "交易编号987654321", "商户订单号SHOP12345")) {
            assertTrue(h.shouldSkip("app", text))
        }
        assertEquals(4, stored.size)
        h.remember("app", "订单号ABCDEFGH-123 交易单号987654321 商户单号SHOP12345")
        assertEquals(4, stored.size)
    }

    @Test fun groupedOrderNumberMatchesCompactOcrWithoutMatchingAnIncompletePrefix() {
        val h = history()
        h.remember("app", "订单号码1234567800124026334 下单时间2026-10-24")
        assertTrue(h.shouldSkip("app", "订单号码 1234 5678 0012 4026 334 复制 下单时间 2026-10-24"))
        assertTrue(h.shouldSkip("app", "订单编号：1234\u00a05678\u00a00012\u00a04026\u00a0334"))
        assertFalse(h.shouldSkip("app", "订单号码 1234 5678 0012 4026 335"))
        assertEquals(1, RecognizedOrderPages.keys("app", "订单号12345678 123456789012345678901234567890123456789012345678901234567890123456789").size)
    }
}
