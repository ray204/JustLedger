package net.ankio.auto.ui.components

import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import org.junit.Assert.*
import org.junit.Test

class LedgerChartDataTest {
    private fun bill(category: String, amount: Double = 10.0) = BillInfoModel().apply {
        cateName = category; money = amount; currency = "CNY"; type = BillType.Expend; state = BillState.Edited
    }
    @Test fun categoryFilterSupportsAllExactAndUnclassifiedWithoutDroppingPendingRows() {
        val rows = listOf(bill("餐饮"), bill("交通"), bill(" ").apply { state = BillState.Wait2Edit })
        assertEquals(3, rows.count { LedgerChartData.matches(it, null) })
        assertEquals(listOf("餐饮"), rows.filter { LedgerChartData.matches(it, "餐饮") }.map { it.cateName })
        assertEquals(1, rows.count { LedgerChartData.matches(it, "未分类") })
        assertEquals(0, rows.count { LedgerChartData.matches(it, "购物") })
    }
    @Test fun totalsExcludePendingMergedNotCountedOtherCurrenciesAndNonExpenses() {
        val rows = listOf(bill("餐饮", .1), bill("餐饮", .2), bill("交通", 7.5),
            bill("待确认", 100.0).apply { state = BillState.Wait2Edit },
            bill("合并", 100.0).apply { groupId = 1 },
            bill("不计", 100.0).apply { flag = BillInfoModel.FLAG_NOT_COUNT },
            bill("外币", 100.0).apply { currency = "USD" },
            bill("收入", 100.0).apply { type = BillType.Income },
            bill("退款", 100.0).apply { type = BillType.IncomeRefund }, bill("零", 0.0))
        assertEquals(listOf(LedgerChartData.Slice("交通", 750), LedgerChartData.Slice("餐饮", 30)), LedgerChartData.categories(rows, "CNY"))
        assertEquals(listOf(LedgerChartData.Slice("外币", 10000)), LedgerChartData.categories(rows, "USD"))
        assertTrue(LedgerChartData.categories(emptyList(), "CNY").isEmpty())
    }
    @Test fun manyCategoriesKeepTotalAndFiveOrFewerRemainIndividuallyNamed() {
        val rows = (1..7).map { bill("类别$it", it.toDouble()) }
        val all = LedgerChartData.categories(rows, "CNY")
        assertEquals(listOf("类别7", "类别6", "类别5", "类别4", "其余3类"), all.map { it.name })
        assertEquals(600L, all.last().cents)
        assertEquals(2800L, all.sumOf { it.cents })
        assertEquals(5, LedgerChartData.categories(rows.take(5), "CNY").size)
        assertTrue(LedgerChartData.categories(rows.take(5), "CNY").none { it.name.startsWith("其余") })
    }
    @Test fun ringHitSelectsCorrectSectorAndCenterOutsideOrRepeatedTapResets() {
        val slices = listOf(LedgerChartData.Slice("餐饮", 75), LedgerChartData.Slice("交通", 25))
        assertEquals(0, LedgerChartData.hit(slices, 40f, 0f, 25f, 60f))
        assertEquals(1, LedgerChartData.hit(slices, -30f, -30f, 25f, 60f))
        assertEquals(-1, LedgerChartData.hit(slices, 0f, 0f, 25f, 60f))
        assertEquals(-1, LedgerChartData.hit(slices, 61f, 0f, 25f, 60f))
        assertEquals(-1, LedgerChartData.hit(emptyList(), 40f, 0f, 25f, 60f))
        assertEquals(0, LedgerChartData.toggle(-1, 0))
        assertEquals(-1, LedgerChartData.toggle(0, 0))
        assertEquals(1, LedgerChartData.toggle(0, 1))
        assertEquals(-1, LedgerChartData.toggle(1, -1))
    }
    @Test fun leaderLabelsStayOrderedSeparatedAndInsideChartForClusteredSlices() {
        assertEquals(listOf(20f, 49f, 78f), LedgerChartData.labelPositions(listOf(0f, 1f, 2f), 20f, 164f, 29f))
        assertEquals(listOf(106f, 135f, 164f), LedgerChartData.labelPositions(listOf(160f, 161f, 180f), 20f, 164f, 29f))
        assertEquals(listOf(50f), LedgerChartData.labelPositions(listOf(50f), 20f, 164f, 29f))
        assertTrue(LedgerChartData.labelPositions(emptyList(), 20f, 164f, 29f).isEmpty())
    }
}
