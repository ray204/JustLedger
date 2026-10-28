package org.ezbook.server.tools

import androidx.room.withTransaction
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.CategoryModel

/** Standalone books no longer receive a category catalog from another accounting app. */
object LocalCategoryDefaults {
    private val expense = listOf("餐饮", "饮品", "交通", "购物", "居家", "水电", "服务", "服饰", "娱乐", "医疗", "学习", "通讯", "旅行", "运动", "美容", "宠物", "育儿", "人情", "汽车", "保险", "税费", "其他", "房租")
    private val income = listOf("工资", "奖金", "兼职收入", "理财收益", "礼金", "其他")

    fun missing(book: String, existing: List<CategoryModel>): List<CategoryModel> =
        listOf(BillType.Expend to expense, BillType.Income to income).flatMap { (type, names) ->
            if (existing.any { it.remoteBookId == book && it.type == type }) emptyList()
            else names.mapIndexed { index, name -> CategoryModel().apply {
                this.name = name
                this.type = type
                remoteBookId = book
                remoteParentId = "-1"
                remoteId = "jl-${MD5HashTable.md5(book)}-${type.name}-$index"
                sort = index
            } }
        }

    fun rentAddition(book: String, existing: List<CategoryModel>): List<CategoryModel> {
        if (existing.any { it.remoteBookId == book && it.type == BillType.Expend && it.name == "房租" }) return emptyList()
        return missing(book, emptyList()).filter { it.type == BillType.Expend && it.name == "房租" }
    }

    suspend fun ensure(book: String) = Db.get().withTransaction {
        val db = Db.get()
        val marker = "justledger.category-defaults.v1.${MD5HashTable.md5(book)}"
        if (db.settingDao().query(marker)?.value != "true") {
            missing(book, db.categoryDao().all()).forEach { db.categoryDao().insert(it) }
            db.settingDao().set(marker, "true")
        }
        val rentMarker = "justledger.category-rent.v2.${MD5HashTable.md5(book)}"
        if (db.settingDao().query(rentMarker)?.value != "true") {
            rentAddition(book, db.categoryDao().all()).forEach { db.categoryDao().insert(it) }
            // Upgrade once; a later intentional deletion must stay deleted.
            db.settingDao().set(rentMarker, "true")
        }
    }
}
