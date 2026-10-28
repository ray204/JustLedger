package org.ezbook.server.tools

import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import org.junit.Assert.*
import org.junit.Test

class OccasionalPolicyTest {
    private fun expense(category: String, amount: Double) = BillInfoModel().apply {
        cateName=category; money=amount; type=BillType.Expend; currency="CNY"; state=BillState.Edited
    }
    @Test fun rentDefaultsToOccasionalAndExplicitDailyChoiceSurvivesCategoryChanges() {
        val rent=expense("居家-房租",3000.0)
        assertTrue(OccasionalPolicy.isOccasional(rent))
        assertFalse(OccasionalPolicy.matches(rent,OccasionalPolicy.Scope.DAILY))
        assertTrue(OccasionalPolicy.matches(rent,OccasionalPolicy.Scope.ALL))
        rent.flag=BillInfoModel.FLAG_NOT_BUDGET
        OccasionalPolicy.setOccasional(rent,false)
        assertFalse(OccasionalPolicy.isOccasional(rent)); assertTrue(rent.hasFlag(BillInfoModel.FLAG_NOT_BUDGET))
        rent.cateName="房租"; assertFalse(OccasionalPolicy.isOccasional(rent))
        OccasionalPolicy.setOccasional(rent,true)
        rent.cateName="旅行"; assertTrue(OccasionalPolicy.isOccasional(rent))
        rent.type=BillType.Income; assertFalse(OccasionalPolicy.isOccasional(rent))
    }
    @Test fun refundFollowsOriginalFrequencyAcrossMonthsWithoutMutatingEitherRecord() {
        val original=expense("房租",3000.0).apply { id=7; time=1 }
        val refund=expense("其他",100.0).apply { id=8; type=BillType.IncomeRefund; originalBillId=7; time=9000000000 }
        assertTrue(OccasionalPolicy.isOccasional(OccasionalPolicy.withOriginal(refund,original)))
        assertEquals(0,refund.flag); assertEquals(0,original.flag)
        OccasionalPolicy.setOccasional(original,false)
        assertFalse(OccasionalPolicy.isOccasional(OccasionalPolicy.withOriginal(refund,original)))
        assertEquals(refund,OccasionalPolicy.withOriginal(refund,original.copy(currency="USD")))
        assertEquals(refund,OccasionalPolicy.withOriginal(refund,null))
    }
    @Test fun dailyAndInclusiveTotalsKeepRefundsCurrencyAndExclusionsConsistent() {
        val rent=expense("房租",3000.0).apply { id=1 }
        val refund=OccasionalPolicy.withOriginal(expense("其他",100.0).apply { type=BillType.IncomeRefund; originalBillId=1 },rent)
        val rows=listOf(rent,refund,expense("餐饮",80.2),expense("餐饮",.1),
            expense("交通",10.0).apply { type=BillType.IncomeRefund },
            expense("工资",5000.0).apply { type=BillType.Income },
            expense("房租",100.0).apply { currency="USD" },
            expense("其他",100.0).apply { groupId=1 },
            expense("其他",100.0).apply { state=BillState.Wait2Edit },
            expense("其他",100.0).apply { flag=BillInfoModel.FLAG_NOT_COUNT },
            expense("其他",100.0).apply { type=BillType.Transfer })
        val totals=OccasionalPolicy.totals(rows,"CNY")
        assertEquals(7030L,totals.dailyExpense);assertEquals(290000L,totals.occasionalExpense)
        assertEquals(297030L,totals.totalExpense);assertEquals(500000L,totals.income)
        assertEquals(70.3,totals.asMap()["expense"]!!,0.0)
        assertEquals(2970.3,totals.asMap()["totalExpense"]!!,0.0)
        assertEquals(10000L,OccasionalPolicy.totals(rows,"USD").occasionalExpense)
        assertEquals(0L,OccasionalPolicy.totals(emptyList(),"CNY").totalExpense)
        assertEquals(-10000L,OccasionalPolicy.totals(listOf(refund),"CNY").occasionalExpense)
    }
}
