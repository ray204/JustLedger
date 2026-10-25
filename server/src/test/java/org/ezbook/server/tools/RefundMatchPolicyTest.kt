package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.constant.BillType
import org.ezbook.server.constant.BillState
import org.ezbook.server.db.model.BillInfoModel

class RefundMatchPolicyTest {
    private val now=1792920000000L
    private fun payment(id:Long=1)=BillInfoModel(id=id,type=BillType.Expend,money=100.0,currency="CNY",bookName="账本",
        state=BillState.Edited,time=now-3600000,shopName="街角咖啡",shopItem="冰拿铁",accountNameFrom="生活卡1234",
        app="com.tencent.mm",transactionNamespace="wechat:transaction",channel="微信")
    private fun refund()=payment(9).copy(type=BillType.IncomeRefund,money=30.0,time=now,originalBillId=0)
    private fun candidate(b:BillInfoModel=payment(),remaining:Double=100.0)=RefundMatchPolicy.Candidate(b,remaining)

    @Test fun partialRefundUsesRemainingAmountAndRejectsInvalidLedgerCandidates() {
        assertTrue(RefundMatchPolicy.eligible(refund(),candidate(remaining=30.0)))
        for (remaining in listOf(29.99,0.0,-1.0,Double.NaN)) assertFalse(RefundMatchPolicy.eligible(refund(),candidate(remaining=remaining)))
        for (b in listOf(payment().copy(bookName="另一本"),payment().copy(currency="USD"),payment().copy(state=BillState.Wait2Edit),
            payment().copy(flag=1),payment().copy(groupId=7),payment().copy(type=BillType.Income),payment(9)))
            assertFalse(RefundMatchPolicy.eligible(refund(),candidate(b)))
        assertFalse(RefundMatchPolicy.eligible(refund().copy(eventStatus="PENDING"),candidate()))
        assertTrue(RefundMatchPolicy.eligible(refund(),candidate(payment().copy(time=now+300000))))
        assertFalse(RefundMatchPolicy.eligible(refund(),candidate(payment().copy(time=now+300001))))
    }
    @Test fun scopedOriginalIdsAndOrderAliasesMatchButConflictsAndDuplicatesDoNot() {
        val b=payment().copy(transactionId="PAYMENT12345678")
        val r=refund().copy(originalTransactionId=b.transactionId,transactionNamespace="com.tencent.mm",shopName="",shopItem="")
        assertEquals(1L,RefundMatchPolicy.local(r,listOf(candidate(b)))?.id)
        assertNull(RefundMatchPolicy.local(r,listOf(candidate(b),candidate(b.copy(id=2)))))
        assertNull(RefundMatchPolicy.local(r.copy(originalTransactionId="DIFFERENT12345"),listOf(candidate(b))))
        assertNull(RefundMatchPolicy.local(r.copy(transactionNamespace="another.provider"),listOf(candidate(b))))
        val order=payment().apply { app="com.sankuai.meituan";ReceiptIdentity.attach(this,"订单编号 ORDER12345678") }
        val returned=refund().apply { app="com.sankuai.meituan";ReceiptIdentity.attach(this,"订单编号 ORDER12345678") }
        assertEquals(1L,RefundMatchPolicy.local(returned,listOf(candidate(order)))?.id)
        ReceiptIdentity.attach(returned,"订单编号 OTHER12345678")
        assertNull(RefundMatchPolicy.local(returned,listOf(candidate(order))))
    }
    @Test fun localFallbackRequiresUniqueCorroborationNeverAmountOrPlatformAlone() {
        assertEquals(1L,RefundMatchPolicy.local(refund(),listOf(candidate()))?.id)
        assertNull(RefundMatchPolicy.local(refund(),listOf(candidate(),candidate(payment(2)))))
        val sparse=refund().copy(shopName="美团",shopItem="美团订单-1234567890")
        assertNull(RefundMatchPolicy.local(sparse,listOf(candidate(payment().copy(shopName="美团",shopItem=sparse.shopItem,money=30.0)))))
        assertEquals(1L,RefundMatchPolicy.local(refund().copy(shopItem=""),listOf(candidate()))?.id)
        assertNull(RefundMatchPolicy.local(refund().copy(shopItem="",time=payment().time+86400001),listOf(candidate())))
        assertEquals(1L,RefundMatchPolicy.local(refund().copy(accountNameFrom="",time=payment().time+30L*86400000),listOf(candidate()))?.id)
        assertNull(RefundMatchPolicy.local(refund().copy(accountNameFrom="",time=payment().time+30L*86400000+1),listOf(candidate())))
    }
    @Test fun aiCoarseFilterExcludesUnrelatedOldAndExhaustedBillsAndNeverHidesExcessAlternatives() {
        val rows=listOf(candidate(),candidate(payment(2).copy(shopName="陌生商店",shopItem="桌子",app="other",transactionNamespace="other",channel="其他",accountNameFrom="另一张卡")),
            candidate(payment(3).copy(time=now-91L*86400000)),candidate(payment(4),0.0))
        assertEquals(listOf(1L),RefundMatchPolicy.forAi(refund(),rows).map { it.bill.id })
        assertEquals(8,RefundMatchPolicy.forAi(refund(),(11L..18L).map { candidate(payment(it)) }).size)
        assertTrue(RefundMatchPolicy.forAi(refund(),(11L..19L).map { candidate(payment(it)) }).isEmpty())
        assertTrue(RefundMatchPolicy.forAi(refund().copy(originalTransactionId="UNFOUND123456"),
            listOf(candidate(payment().copy(transactionId="DIFFERENT123456")))).isEmpty())
    }
    @Test fun originalPaidAmountTimeAndChannelBridgeBankRecordsWithoutOrderIds() {
        val original=payment().copy(shopName="商户未知",shopItem="",app="bank.app",transactionNamespace="bank.app",channel="财付通-微信支付",accountNameFrom="")
        val r=refund().copy(shopName="美团",shopItem="美团订单-1234567890",originalTransactionId="ORIGINAL12345678",channel="微信",accountNameFrom="",
            detailsJson=com.google.gson.Gson().toJson(mapOf("原支付金额" to "100.00","支付时间" to (original.time+27000).toString())))
        assertEquals(1L,RefundMatchPolicy.local(r,listOf(candidate(original)))?.id)
        assertNull(RefundMatchPolicy.local(r,listOf(candidate(original),candidate(original.copy(id=2)))))
        assertNull(RefundMatchPolicy.local(r.copy(detailsJson="{}"),listOf(candidate(original))))
        assertNull(RefundMatchPolicy.local(r,listOf(candidate(original.copy(money=80.0)))))
        assertNull(RefundMatchPolicy.local(r,listOf(candidate(original.copy(time=original.time-60001)))))
        val withAlias=payment().copy(transactionId="MERCHANT123456",transactionNamespace="other.app",app="other.app")
        ReceiptIdentity.attach(withAlias,"微信交易单号 ORIGINAL12345678")
        assertEquals(1L,RefundMatchPolicy.local(refund().copy(originalTransactionId="ORIGINAL12345678"),listOf(candidate(withAlias)))?.id)
    }
}
