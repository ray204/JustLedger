package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.CategoryModel

class LocalCategoryDefaultsTest {
    @Test fun emptyStandaloneBookHasSelectableExpenseAndIncomeCategories() {
        val rows = LocalCategoryDefaults.missing("local-book", emptyList())
        assertTrue(rows.filter { it.type == BillType.Expend }.map { it.name }.containsAll(listOf("餐饮", "饮品", "交通", "服务", "其他")))
        assertTrue(rows.filter { it.type == BillType.Income }.map { it.name }.containsAll(listOf("工资", "奖金", "理财收益")))
        assertEquals(28, rows.size)
        assertEquals(rows.size, rows.map { it.remoteId }.toSet().size)
        assertTrue(rows.all { it.remoteBookId == "local-book" && it.remoteParentId == "-1" })
        assertTrue(LocalCategoryDefaults.missing("local-book", rows).isEmpty())
    }

    @Test fun customCatalogIsPreservedAndBooksDoNotShareCategoryIds() {
        val custom=CategoryModel().apply { name="自定义"; type=BillType.Expend; remoteBookId="A" }
        assertTrue(LocalCategoryDefaults.missing("A", listOf(custom)).all { it.type == BillType.Income })
        val a=LocalCategoryDefaults.missing("A", emptyList())
        val b=LocalCategoryDefaults.missing("B", listOf(custom))
        assertEquals(28,b.size)
        assertTrue(a.map { it.remoteId }.toSet().intersect(b.map { it.remoteId }.toSet()).isEmpty())
        assertEquals("自定义",custom.name)
    }
}
