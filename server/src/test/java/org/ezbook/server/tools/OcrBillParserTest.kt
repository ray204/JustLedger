package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class OcrBillParserTest {
    private val meituan = "取餐码123\n订单信息\n自取地址\n测试地址\n商品费用\n瑞幸咖啡（测试店）\n¥9.9\n轻椰茉莉拿铁\n折\n￥20\nX1\n合计\n已优惠12.1元￥7.9"
    private val wechat = "支付成功\n￥7.90\n收款方\n美团\n商品说明\n测试商品\n支付方式\n招商银行信用卡\n交易单号\n4200001234567890\n交易时间\n2026-10-24 15:29:01"

    @Test fun meituanSeparatesPaidTotalFromListPricesAndDiscounts() {
        val draft = requireNotNull(OcrBillParser.parse("com.sankuai.meituan", meituan))
        assertEquals(7.9, draft.money, 0.001)
        assertEquals("瑞幸咖啡（测试店）", draft.shopName)
        assertEquals("轻椰茉莉拿铁", draft.shopItem)
        assertEquals("", draft.transactionId)
        assertEquals("", draft.accountNameFrom)
    }
    @Test fun wechatPreservesLabelledIdentityAccountAndGoods() {
        val draft = requireNotNull(OcrBillParser.parse("com.tencent.mm", wechat))
        assertEquals(7.9, draft.money, 0.001)
        assertEquals("美团", draft.shopName)
        assertEquals("测试商品", draft.shopItem)
        assertEquals("招商银行信用卡", draft.accountNameFrom)
        assertEquals("4200001234567890", draft.transactionId)
        assertEquals("wechat:transaction", draft.transactionNamespace)
        assertEquals("2026-10-24 15:29:01", draft.timeText)
    }
    @Test fun pendingRefundAndUnknownAppCannotBecomeExpenses() {
        for (status in listOf("待付款", "立即支付", "支付失败", "退款成功", "转账成功", "交易关闭")) {
            assertNull(OcrBillParser.parse("com.tencent.mm", wechat + "\n" + status))
        }
        assertNull(OcrBillParser.parse("net.ankio.auto", meituan))
        assertNull(OcrBillParser.parse("com.sankuai.meituan", meituan.replace("取餐码123", "")))
        assertNull(OcrBillParser.parse("com.tencent.mm", wechat.replace("支付成功", "")))
    }
    @Test fun ambiguousMissingAndInvalidAmountsAreRejected() {
        assertNull(OcrBillParser.parse("com.tencent.mm", wechat + "\n￥8.90"))
        for (amount in listOf("￥0", "￥7.901", "￥1,000", "￥NaN", "USD7.9", "￥01.00")) {
            assertNull(OcrBillParser.parse("com.tencent.mm", wechat.replace("￥7.90", amount)))
        }
        assertNull(OcrBillParser.parse("com.sankuai.meituan", meituan.replace("￥7.9", "")))
        assertNull(OcrBillParser.parse("com.tencent.mm", "支付成功\n￥7.90"))
    }
    @Test fun merchantOrderNumberHasSeparateNamespace() {
        val draft = requireNotNull(OcrBillParser.parse("com.sankuai.meituan", meituan + "\n订单编号：9876543210123456"))
        assertEquals("9876543210123456", draft.transactionId)
        assertEquals("meituan:order", draft.transactionNamespace)
    }
    @Test fun wechatSignedAmountWorksButConflictingLabelsAndForeignCurrencyDoNot() {
        assertEquals(7.9, requireNotNull(OcrBillParser.parse("com.tencent.mm", wechat.replace("￥7.90", "-7.90"))).money, 0.001)
        assertNull(OcrBillParser.parse("com.tencent.mm", wechat + "\n支付金额：￥8.90\n付款金额：￥9.90"))
        assertNull(OcrBillParser.parse("com.tencent.mm", wechat + "\nUSD"))
    }
    @Test fun expandedMeituanDetailsIgnoreMarketingAndReadInlineOrderNumber() {
        val text=meituan.replace("订单信息","订单号码1234567890123456\n支付方式在线支付")
            .replace("瑞幸咖啡（测试店）", "瑞幸咖啡（测试店）>\n进商家粉丝群")
        val draft=requireNotNull(OcrBillParser.parse("com.sankuai.meituan",text))
        assertEquals("轻椰茉莉拿铁",draft.shopItem);assertEquals("瑞幸咖啡（测试店）",draft.shopName)
        assertEquals("1234567890123456",draft.transactionId);assertEquals("",draft.accountNameFrom)
        assertEquals("12.1 元",draft.extra["优惠金额"]);assertEquals("1",draft.extra["商品数量"])
    }
}
