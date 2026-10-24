package org.ezbook.server.tools

import org.ezbook.server.db.model.BillInfoModel
import org.junit.Assert.*
import org.junit.Test

class ReceiptIdentityTest {
    @Test fun labelledIdsAreTypedScopedAndGroupedDigitsAreNormalized() {
        assertEquals(mapOf("app|order" to "1234567800124026334", "wechat|payment" to "PAY12345678"),
            ReceiptIdentity.extract("app", "订单编号 1234 5678 0012 4026 334\n微信支付交易单号PAY12345678"))
        assertTrue(ReceiptIdentity.extract("app", "取餐码1234 金额12.00 订单号1234567").isEmpty())
        assertTrue(ReceiptIdentity.extract("app", "订单号12345678 订单号87654321").isEmpty())
        assertTrue(ReceiptIdentity.extract("", "订单号12345678").isEmpty())
        assertEquals("1".repeat(64),ReceiptIdentity.extract("app", "订单号"+"1".repeat(64))["app|order"])
        assertTrue(ReceiptIdentity.extract("app", "订单号"+"1".repeat(65)).isEmpty())
    }

    @Test fun sourceEvidenceReplacesModelSuppliedMetadataAndStaysOutOfUserDetails() {
        val bill=BillInfoModel(app="app",detailsJson="""{"数量":1,"_receiptKeysV1":{"app|order":"FAKE12345678"}}""")
        ReceiptIdentity.attach(bill,"订单号REAL12345678")
        assertEquals(mapOf("app|order" to "REAL12345678"),ReceiptIdentity.keys(bill))
        assertEquals(listOf("数量：1"),ReceiptDetails.lines(bill.detailsJson))
        ReceiptIdentity.attach(bill,"未提供订单编号")
        assertTrue(ReceiptIdentity.keys(bill).isEmpty())
        assertEquals(listOf("数量：1"),ReceiptDetails.lines(bill.detailsJson))
    }

    @Test fun learnedAliasesPreserveExistingFactsAndCannotOverwriteKnownIdentifiers() {
        val known=BillInfoModel(app="app",detailsJson="""{"数量":2}""")
        ReceiptIdentity.attach(known,"订单号ORDER123456")
        val incoming=BillInfoModel(app="app")
        ReceiptIdentity.attach(incoming,"订单号ORDER123456 微信支付交易单号PAYMENT12345")
        assertTrue(ReceiptIdentity.remember(known,incoming))
        assertEquals(mapOf("app|order" to "ORDER123456","wechat|payment" to "PAYMENT12345"), ReceiptIdentity.keys(known))
        assertEquals(listOf("数量：2"),ReceiptDetails.lines(known.detailsJson))
        assertFalse(ReceiptIdentity.remember(known,incoming))
        ReceiptIdentity.attach(incoming,"订单号DIFFERENT123")
        assertFalse(ReceiptIdentity.remember(known,incoming))
        assertEquals("ORDER123456",ReceiptIdentity.keys(known)["app|order"])
    }
}
