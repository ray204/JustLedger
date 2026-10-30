package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.CategoryModel

class LocalCategoryDefaultsTest {
    @Test fun emptyStandaloneBookHasSelectableExpenseAndIncomeCategories() {
        val rows = LocalCategoryDefaults.missing("local-book", emptyList())
        assertTrue(rows.filter { it.type == BillType.Expend }.map { it.name }.containsAll(listOf("餐饮", "饮品", "交通", "服务", "其他", "房租", "AI")))
        assertTrue(rows.filter { it.type == BillType.Income }.map { it.name }.containsAll(listOf("工资", "奖金", "理财收益")))
        assertEquals(30, rows.size)
        assertEquals(rows.size, rows.map { it.remoteId }.toSet().size)
        assertTrue(rows.all { it.remoteBookId == "local-book" && it.remoteParentId == "-1" })
        assertTrue(LocalCategoryDefaults.missing("local-book", rows).isEmpty())
    }

    @Test fun customCatalogIsPreservedAndBooksDoNotShareCategoryIds() {
        val custom=CategoryModel().apply { name="自定义"; type=BillType.Expend; remoteBookId="A" }
        assertTrue(LocalCategoryDefaults.missing("A", listOf(custom)).all { it.type == BillType.Income })
        val a=LocalCategoryDefaults.missing("A", emptyList())
        val b=LocalCategoryDefaults.missing("B", listOf(custom))
        assertEquals(30,b.size)
        assertTrue(a.map { it.remoteId }.toSet().intersect(b.map { it.remoteId }.toSet()).isEmpty())
        assertEquals("自定义",custom.name)
    }
    @Test fun rentUpgradeOnlyAddsMissingRentWithoutReplacingExistingCategories() {
        val old=LocalCategoryDefaults.missing("A", emptyList()).filter { it.name != "房租" }
        val added=LocalCategoryDefaults.rentAddition("A",old)
        assertEquals(listOf("房租"),added.map { it.name })
        assertTrue(LocalCategoryDefaults.rentAddition("A",old+added).isEmpty())
        assertEquals(old.size,old.map { it.remoteId }.toSet().size)
        assertEquals(1,LocalCategoryDefaults.rentAddition("B",old+added).size)
        val custom=CategoryModel().apply { name="房租"; type=BillType.Expend; remoteBookId="A"; remoteId="custom-rent" }
        assertTrue(LocalCategoryDefaults.rentAddition("A",listOf(custom)).isEmpty())
        assertEquals("custom-rent",custom.remoteId)
    }
    @Test fun aiUpgradePreservesCustomCategoryAndExistingIdsAcrossBooks() {
        val old = LocalCategoryDefaults.missing("A", emptyList()).filter { it.name != "AI" }
        val ids = old.map { it.remoteId }
        val added = LocalCategoryDefaults.aiAddition("A", old)
        assertEquals(listOf("AI"), added.map { it.name })
        assertEquals(BillType.Expend, added.single().type)
        assertFalse(added.single().remoteId in ids)
        assertEquals(ids, old.map { it.remoteId })
        assertTrue(LocalCategoryDefaults.aiAddition("A", old + added).isEmpty())
        assertEquals(1, LocalCategoryDefaults.aiAddition("B", old + added).size)
        val custom = CategoryModel().apply {
            name = "ai"; type = BillType.Expend; remoteBookId = "A"; remoteId = "custom-ai"
        }
        assertTrue(LocalCategoryDefaults.aiAddition("A", listOf(custom)).isEmpty())
        assertEquals("custom-ai", custom.remoteId)
        custom.type = BillType.Income
        assertEquals(1, LocalCategoryDefaults.aiAddition("A", listOf(custom)).size)
    }

}
