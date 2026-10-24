package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel

class OcrRepeatPolicyTest {
    private fun bill() = BillInfoModel(money=12.0, currency="CNY", type=BillType.Expend,
        app="payment.app", transactionNamespace="payment:order", transactionId="123456789012",
        shopName="咖啡（示例店）", shopItem="拿铁", bookName="默认账本",
        time=DateUtils.toEpochMillis("2026-10-24 15:28:50"))

    @Test fun automaticRecognitionOnlySilencesAnExistingGroundedPayment() {
        val incoming=bill(); val known=bill().copy(id=1)
        assertTrue(OcrRepeatPolicy.eligible("OCR",manual=false,preview=false))
        assertTrue(OcrRepeatPolicy.samePayment(incoming,known,"不同布局 订单编号 1234 5678 9012 已完成"))
        assertTrue(OcrRepeatPolicy.samePayment(incoming.copy(transactionNamespace="payment.app"),known,"单号：123456789012"))
        assertTrue(OcrRepeatPolicy.samePayment(incoming.copy(money=20.0),known,"单号：123456789012 原价20.00"))
        assertFalse(OcrRepeatPolicy.samePayment(incoming,known,"模型编造了原文不存在的单号"))
        assertFalse(OcrRepeatPolicy.samePayment(incoming,known,"单号：1234567890129"))
    }

    @Test fun manualAndPreviewRequestsAlwaysKeepReviewAvailable() {
        assertFalse(OcrRepeatPolicy.eligible("OCR",manual=true,preview=false))
        assertFalse(OcrRepeatPolicy.eligible("OCR",manual=false,preview=true))
        assertFalse(OcrRepeatPolicy.eligible("NOTICE",manual=false,preview=false))
        assertFalse(OcrRepeatPolicy.eligible("SMS",manual=false,preview=false))
    }

    @Test fun sameAmountCannotHideDifferentTransactionsCurrenciesAccountsOrRefunds() {
        val incoming=bill()
        for (known in listOf(bill().copy(transactionId="other-order"),
            bill().copy(currency="USD"),bill().copy(bookName="另一账本"),bill().copy(type=BillType.Income),
            bill().copy(app="other.app",transactionNamespace="other:order"))) {
            assertFalse(OcrRepeatPolicy.samePayment(incoming,known,"123456789012"))
        }
        assertTrue(OcrRepeatPolicy.samePayment(incoming.copy(accountNameFrom="银行卡A"),bill().copy(accountNameFrom="微信"),"123456789012"))
        assertFalse(OcrRepeatPolicy.samePayment(incoming.copy(transactionId="", accountNameFrom="银行卡A"),bill().copy(transactionId="",accountNameFrom="银行卡B"),"咖啡(示例店) 拿铁 2026-10-24 15:28:50"))
        val refund=bill().copy(type=BillType.IncomeRefund)
        assertFalse(OcrRepeatPolicy.samePayment(refund,refund,"原支付单号123456789012"))
        assertTrue(OcrRepeatPolicy.samePayment(refund.copy(refundId="REFUND123"),refund.copy(refundId="REFUND123"),"退款单号REFUND123"))
    }

    @Test fun absentIdsRequireExactMerchantItemAndGroundedSecondPrecisionTime() {
        val source=bill().copy(transactionId="")
        val raw="咖啡(示例店) 拿铁 实付12.00 支付时间2026年10月24日15时28分50秒"
        assertTrue(OcrRepeatPolicy.samePayment(source,source,raw))
        assertFalse(OcrRepeatPolicy.samePayment(source,source.copy(money=13.0),raw))
        assertFalse(OcrRepeatPolicy.samePayment(source,source.copy(time=source.time+1000),raw))
        assertFalse(OcrRepeatPolicy.samePayment(source,source.copy(shopName="另一个商户"),raw))
        assertFalse(OcrRepeatPolicy.samePayment(source,source.copy(shopItem="美式"),raw))
        assertFalse(OcrRepeatPolicy.samePayment(source,source,"咖啡(示例店) 拿铁 实付12.00 支付时间2026-10-24 15:28"))
        assertFalse(OcrRepeatPolicy.samePayment(source.copy(shopItem=""),source.copy(shopItem=""),raw))
        assertFalse(OcrRepeatPolicy.samePayment(source,source,raw.replace("拿铁","")))
    }

    @Test fun anyTypedOrderPaymentOrMerchantIdentifierSurvivesReopeningAndPriceChanges() {
        val known=bill().copy(transactionId="")
        ReceiptIdentity.attach(known,"订单号ORDER123456 交易单号PAYMENT12345 商户单号MERCHANT1234 银行流水号BANK12345678")
        val reopened=known.copy(detailsJson=known.detailsJson)
        for (text in listOf("订单编号ORDER123456", "支付流水号PAYMENT12345", "商户订单号MERCHANT1234", "银行流水号BANK12345678")) {
            assertTrue(text, OcrRepeatPolicy.samePayment(bill().copy(transactionId="",money=99.0),reopened,text))
        }
        assertFalse(OcrRepeatPolicy.samePayment(bill().copy(transactionId=""),reopened,"订单号PAYMENT12345"))
        assertFalse(OcrRepeatPolicy.samePayment(bill().copy(transactionId=""),reopened,"订单号ORDER999999 交易单号PAYMENT12345"))
        assertFalse(OcrRepeatPolicy.samePayment(bill().copy(transactionId=""),reopened,"订单号ORDER123456 订单号ORDER999999 交易单号PAYMENT12345"))
    }

    @Test fun providerQualifiedPaymentCanBridgeAppsButUnqualifiedOrderCannot() {
        val known=bill().copy(app="com.tencent.mm",transactionId="")
        ReceiptIdentity.attach(known,"交易单号PAYMENT12345")
        val incoming=bill().copy(app="com.sankuai.meituan",transactionNamespace="",transactionId="")
        assertTrue(OcrRepeatPolicy.samePayment(incoming,known,"微信支付交易单号PAYMENT12345"))
        assertFalse(OcrRepeatPolicy.samePayment(incoming,known,"订单号PAYMENT12345"))
        assertFalse(OcrRepeatPolicy.samePayment(incoming,known,"交易单号PAYMENT12345"))
        assertFalse(OcrRepeatPolicy.samePayment(incoming,known,"支付宝交易号PAYMENT12345"))
    }

    @Test fun secondPrecisionMerchantAmountAndSpecificAccountFormAnIndependentKey() {
        val known=bill().copy(transactionId="",shopItem="",accountNameFrom="招商银行(1234)")
        val raw="咖啡(示例店) 实付12.00 招商银行(1234) 支付时间2026-10-24 15:28:50"
        assertTrue(OcrRepeatPolicy.samePayment(known.copy(time=known.time+999),known,raw))
        assertFalse(OcrRepeatPolicy.samePayment(known.copy(time=known.time+1000),known,raw))
        assertFalse(OcrRepeatPolicy.samePayment(known.copy(money=13.0),known,raw))
        assertFalse(OcrRepeatPolicy.samePayment(known,known,raw.replace("招商银行(1234)","")))
        assertFalse(OcrRepeatPolicy.samePayment(known.copy(accountNameFrom="微信"),known.copy(accountNameFrom="微信"),raw+" 微信"))
        assertFalse(OcrRepeatPolicy.samePayment(known,known,raw.replace(":50","")))
    }

    @Test fun repeatedRefundNeedsItsOwnIdentifierAndNeverOnlyTheOriginalOrder() {
        val known=bill().copy(type=BillType.IncomeRefund,transactionId="")
        ReceiptIdentity.attach(known,"订单号ORDER123456 退款单号REFUND123456")
        assertTrue(OcrRepeatPolicy.samePayment(known,known,"退款流水号REFUND123456"))
        assertFalse(OcrRepeatPolicy.samePayment(known,known,"订单号ORDER123456"))
        assertFalse(OcrRepeatPolicy.samePayment(known,known,"订单号ORDER123456 退款单号REFUND999999"))
    }
}
