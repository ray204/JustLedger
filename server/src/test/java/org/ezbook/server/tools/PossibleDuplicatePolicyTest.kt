package org.ezbook.server.tools

import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.constant.BillType
import org.junit.Assert.*
import org.junit.Test

class PossibleDuplicatePolicyTest {
    private fun source()=BillInfoModel(id=1,type=BillType.Expend,app="payment.app",money=12.0,time=1_000_000,shopName="咖啡店",shopItem="拿铁",bookName="账本",currency="CNY")

    @Test fun nearbyCrossChannelRecordsAreSuggestedOnlyWithConsistentFacts() {
        val a=source(); val b=a.copy(id=2,app="bank.sms",time=a.time+300_000)
        assertTrue(PossibleDuplicatePolicy.plausible(a,b))
        assertFalse(PossibleDuplicatePolicy.plausible(a,b.copy(time=b.time+1)))
        for (other in listOf(b.copy(money=12.01),b.copy(shopName="另一个商户"),b.copy(shopItem="美式"),
            b.copy(bookName="另一个账本"),b.copy(currency="USD"),b.copy(type=BillType.Income),b.copy(time=0))) {
            assertFalse(PossibleDuplicatePolicy.plausible(a,other))
        }
        assertFalse(PossibleDuplicatePolicy.plausible(a,a))
    }

    @Test fun bareAmountAndTimeHaveATighterWindowAndRefundsNeedOwnIdentity() {
        val a=source().copy(shopName="",shopItem="")
        assertTrue(PossibleDuplicatePolicy.plausible(a,a.copy(id=2,time=a.time+60_000)))
        assertFalse(PossibleDuplicatePolicy.plausible(a,a.copy(id=2,time=a.time+60_001)))
        assertTrue(PossibleDuplicatePolicy.plausible(a.copy(accountNameFrom="招商卡1234"),
            a.copy(id=2,time=a.time+200_000,accountNameFrom="招商卡1234")))
        val refund=a.copy(type=BillType.IncomeRefund)
        assertFalse(PossibleDuplicatePolicy.plausible(refund,refund.copy(id=2)))
        assertFalse(PossibleDuplicatePolicy.plausible(a.copy(transactionId="ORDER111",transactionNamespace="app"),
            a.copy(id=2,transactionId="ORDER222",transactionNamespace="app")))
        ReceiptIdentity.attach(a,"订单号ORDER11111")
        val other=a.copy(id=2)
        ReceiptIdentity.attach(other,"订单号ORDER22222")
        assertFalse(PossibleDuplicatePolicy.plausible(a,other))
    }
}
