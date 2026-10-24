package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.constant.BillType

class EnrichmentPolicyTest {
    private fun target() = BillInfoModel(id=1, money=7.9, type=BillType.Expend, currency="CNY",
        time=123, shopName="美团", channel="美团", cateName="其他", remark="美团", auto=true)
    private fun source() = BillInfoModel(id=2, money=7.9, type=BillType.Expend, currency="CNY",
        time=999, shopName="瑞幸咖啡", shopItem="轻椰茉莉拿铁", channel="微信", cateName="餐饮",
        accountNameFrom="招商银行", transactionId="4200001234", transactionNamespace="wechat:transaction")

    @Test fun enrichesPlaceholdersWithoutChangingLedgerAmountTimeOrIdentity() {
        val target = target()
        assertTrue(EnrichmentPolicy.enrich(source(),target))
        assertEquals(1L,target.id); assertEquals(123L,target.time); assertEquals(7.9,target.money,0.001)
        assertEquals("瑞幸咖啡",target.shopName); assertEquals("轻椰茉莉拿铁",target.shopItem)
        assertEquals("招商银行",target.accountNameFrom); assertEquals("微信",target.channel)
        assertEquals("餐饮",target.cateName); assertEquals("瑞幸咖啡 · 轻椰茉莉拿铁",target.remark)
        assertEquals("4200001234",target.transactionId)
        assertFalse(EnrichmentPolicy.enrich(source(),target))
    }
    @Test fun manualValuesSurviveEvenWhenTheyLookLikePlaceholders() {
        val target = target().apply { auto=false; shopItem="我的商品"; accountNameFrom="我的卡"; remark="午餐备注" }
        EnrichmentPolicy.enrich(source(),target)
        assertEquals("美团",target.shopName); assertEquals("其他",target.cateName)
        assertEquals("我的商品",target.shopItem); assertEquals("我的卡",target.accountNameFrom)
        assertEquals("午餐备注",target.remark); assertEquals("美团",target.channel)
    }
    @Test fun rejectsAmountCurrencyTypeBookAndConflictingIdentity() {
        val source=source()
        for (target in listOf(target().apply { money=8.0 }, target().apply { currency="USD" },
            target().apply { type=BillType.Income }, target().apply { bookName="另一个账本" },
            target().apply { transactionNamespace=source.transactionNamespace; transactionId="another-order" })) {
            assertFalse(EnrichmentPolicy.compatible(source,target))
            assertThrows(IllegalArgumentException::class.java) { EnrichmentPolicy.enrich(source,target) }
        }
    }
    @Test fun differentProviderIdentityRemainsAnAliasWithoutOverwritingOriginal() {
        val target=target().apply { transactionId="merchant-order"; transactionNamespace="meituan:order" }
        EnrichmentPolicy.enrich(source(),target)
        assertEquals("merchant-order",target.transactionId)
        assertEquals("meituan:order",target.transactionNamespace)
    }

    @Test fun uniqueRecognitionFillsMissingFactsEvenWithDifferentReadPriceButPreservesUserValues() {
        val target=target().apply { auto=false; shopName="用户店名"; remark="用户备注" }
        assertTrue(EnrichmentPolicy.enrichRecognized(source().copy(money=9.9),target))
        assertEquals(7.9,target.money,0.001); assertEquals(123L,target.time)
        assertEquals("用户店名",target.shopName); assertEquals("用户备注",target.remark)
        assertEquals("轻椰茉莉拿铁",target.shopItem)
        assertEquals("其他",target.cateName)
        assertFalse(EnrichmentPolicy.enrichRecognized(source().copy(money=9.9),target))
    }
}
