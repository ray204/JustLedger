package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class OcrPagePolicyTest {
    private val receipt="支付成功\n￥7.90\n收款方\n测试商户\n商品说明\n测试商品"

    @Test fun wechatHomeRejectsPaymentAndMarketingSnippetsBeforeLocalFallback() {
        for (snippet in listOf("咖啡群\n新品组合7.90元", receipt)) {
            val home="微信(7)\n文件传输助手\n$snippet\n微信\n通讯录\n发现\n我"
            assertTrue(OcrPagePolicy.isNonTransaction("com.tencent.mm",home))
            assertNull(OcrBillParser.parse("com.tencent.mm",home))
        }
        assertFalse(OcrPagePolicy.isNonTransaction("com.tencent.mm",receipt))
        assertEquals(7.9,requireNotNull(OcrBillParser.parse("com.tencent.mm",receipt)).money,0.001)
    }

    @Test fun listAndCartScreensAreNotReceiptsButOrderDetailHeadingsRemainAllowed() {
        for (title in listOf("全部订单","我的订单","购物车","聊天记录"))
            assertTrue(OcrPagePolicy.isNonTransaction("order.app","$title\n测试商户\n已完成18.00元"))
        assertFalse(OcrPagePolicy.isNonTransaction("order.app","订单已完成\n订单号码123456789\n合计￥18.00\n全部订单"))
        assertFalse(OcrPagePolicy.isNonTransaction("com.tencent.mm","交易详情\n商户名称：通讯录发现我\n支付成功\n￥18.00"))
    }
}
