package net.ankio.auto.ui.utils

import androidx.annotation.DrawableRes
import net.ankio.auto.R
import java.util.Locale

/** Local, offline category artwork shared by ledger rows and category selectors. */
object CategoryIcons {
    private val categories = listOf(
        R.drawable.ledger_category_food to listOf("餐饮", "餐食", "吃饭", "早餐", "午餐", "晚餐", "外卖", "零食", "美食", "伙食", "food", "dining"),
        R.drawable.ledger_category_coffee to listOf("咖啡", "饮品", "奶茶", "茶饮", "coffee", "drinks"),
        R.drawable.ledger_category_transport to listOf("交通", "公交", "地铁", "通勤", "打车", "出租车", "出行", "transport", "taxi"),
        R.drawable.ledger_category_service to listOf("服务", "维修", "家政", "保洁", "洗衣", "快递", "service"),
        R.drawable.ledger_category_shopping to listOf("购物", "百货", "网购", "日用", "超市", "采购", "shopping", "groceries"),
        R.drawable.ledger_category_home to listOf("居家", "住房", "房租", "房贷", "家居", "物业", "装修", "home", "housing"),
        R.drawable.ledger_category_utilities to listOf("水电", "电费", "水费", "燃气", "utilities"),
        R.drawable.ledger_category_clothing to listOf("服饰", "衣服", "服装", "鞋", "穿搭", "clothing"),
        R.drawable.ledger_category_entertainment to listOf("娱乐", "游戏", "电影", "影音", "休闲", "entertainment", "games"),
        R.drawable.ledger_category_health to listOf("医疗", "医药", "看病", "药品", "健康", "医院", "health", "medical"),
        R.drawable.ledger_category_education to listOf("学习", "教育", "培训", "书籍", "图书", "课程", "文具", "education", "books"),
        R.drawable.ledger_category_telecom to listOf("通讯", "通信", "话费", "宽带", "网络", "telecom", "phone"),
        R.drawable.ledger_category_travel to listOf("旅行", "旅游", "酒店", "机票", "住宿", "travel"),
        R.drawable.ledger_category_beauty to listOf("美容", "美发", "护肤", "化妆", "美妆", "理发", "beauty"),
        R.drawable.ledger_category_pet to listOf("宠物", "猫咪", "狗狗", "pet", "pets"),
        R.drawable.ledger_category_sport to listOf("运动", "健身", "体育", "游泳", "sport", "fitness"),
        R.drawable.ledger_category_gift to listOf("人情", "礼物", "礼金", "红包", "社交", "捐赠", "gift", "gifts"),
        R.drawable.ledger_category_salary to listOf("工资", "薪资", "薪水", "奖金", "薪酬", "收入", "salary", "income"),
        R.drawable.ledger_category_finance to listOf("理财", "投资", "基金", "股票", "收益", "利息", "finance", "investment"),
        R.drawable.ledger_category_insurance to listOf("保险", "保障", "社保", "insurance"),
        R.drawable.ledger_category_tax to listOf("税费", "税款", "手续费", "缴税", "tax", "fees"),
        R.drawable.ledger_category_child to listOf("育儿", "母婴", "孩子", "宝宝", "儿童", "child", "baby"),
        R.drawable.ledger_category_car to listOf("汽车", "加油", "停车", "车险", "养车", "car", "fuel"),
        R.drawable.ledger_category_transfer to listOf("转账", "还款", "transfer"),
        R.drawable.ledger_category_refund to listOf("退款", "退货", "refund"),
        R.drawable.ledger_category_other to listOf("其他", "未分类", "other"),
    )
    @DrawableRes
    fun drawable(name: String): Int {
        // Prefer a specific subcategory; fall back to its parent, then the generic mark.
        val parts = name.split('-', '－', '—', '–', '/', '／', '>', '＞', '·').map { it.trim().lowercase(Locale.ROOT) }.reversed()
        for (part in parts) {
            categories.firstOrNull { (_, aliases) -> aliases.any { part == it } }?.let { return it.first }
            categories.firstOrNull { (_, aliases) -> aliases.any { alias ->
                alias.any { it.code > 127 } && part.contains(alias)
            } }?.let { return it.first }
        }
        return R.drawable.ledger_category_other
    }
}
