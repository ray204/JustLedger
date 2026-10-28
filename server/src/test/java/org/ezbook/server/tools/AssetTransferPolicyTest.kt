package org.ezbook.server.tools

import org.ezbook.server.ai.tools.ReceiptResponseParser
import org.junit.Assert.*
import org.junit.Test

class AssetTransferPolicyTest {
    @Test fun bankRedemptionAndPrincipalMaturityAreNotIncomeEvenWhenAmountIncludesProfit() {
        listOf(
            "您尾号为1234的账户赎回（稳利十四天持有期理财），资金1200元已划入您的账户，合同号为：987654",
            "{\"title\":\"账户变动\",\"text\":\"您的账户赎回（固收持有期产品），资金1000元已划入您的账户\"}",
            "理财赎回成功，到帐本金1000元，收益20元，到账总额1020元",
            "理财赎回成功1020元，其中收益到账20元",
            "定期存款到期兑付，本息共1020元已到账",
            "基金申购成功1000元", "余额宝转出至余额100元"
        ).forEach { assertTrue(it,AssetTransferPolicy.isInternalMovement(it)) }
    }
    @Test fun separateProfitNormalPaymentsRefundsAndTransfersRemainRecognizable() {
        listOf("理财收益到账20元", "基金分红已发放30元", "赎回收益到账10元", "存款利息入账5元",
            "微信转账到账100元", "房租支付成功3000元", "商家退款到账20元",
            "购买理财课程支付200元", "购买基金赎回教程支付200元", "餐饮消费20元，理财申购优惠活动正在进行", "余额1000元，餐饮消费20元", "从余额宝转出30元支付商户", "工资到账5000元，已自动转入余额宝",
            "{\"eventId\":\"基金赎回\",\"text\":\"商家退款到账10元\"}"
        ).forEach { assertFalse(it,AssetTransferPolicy.isInternalMovement(it)) }
    }
    @Test fun aiCanRejectSemanticAssetMovementWithoutRequiringBillFields() {
        assertNull(ReceiptResponseParser.parse("```json\n{\"pageType\":\"other\"}\n```"))
        assertNotNull(ReceiptResponseParser.parse("{\"pageType\":\"receipt\",\"money\":12,\"type\":\"Income\",\"cateName\":\"理财收益\"}"))
        assertTrue(AssetTransferPolicy.AI_INSTRUCTION.contains("含本金与收益"))
    }
}
