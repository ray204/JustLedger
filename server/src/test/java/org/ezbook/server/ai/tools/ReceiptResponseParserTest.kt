package org.ezbook.server.ai.tools

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ReceiptResponseParserTest {
    private val valid="""{"money":7.9,"type":"Expend","shopName":"商户","shopItem":"拿铁","extra":{"数量":1,"明细":[{"名称":"拿铁","规格":"大杯"}]}}"""
    @Test fun acceptsBareFencedAndProseJsonWithExtensibleFacts() {
        for (text in listOf(valid,"```json\n$valid\n```","```\n$valid\n```","结果如下：\n$valid\n完成。")) {
            val bill=requireNotNull(ReceiptResponseParser.parse(text))
            assertEquals(7.9,bill.money,0.001);assertEquals("拿铁",bill.shopItem)
            assertTrue(bill.detailsJson.contains("大杯"));assertTrue(bill.detailsJson.contains("数量"))
        }
    }
    @Test fun modelCannotWriteDatabaseOrMergeState() {
        val bill=requireNotNull(ReceiptResponseParser.parse("""{"money":1,"type":"Expend","id":999,"groupId":2,"auto":true,"flag":99,"配送方式":"自取"}"""))
        assertEquals(0L,bill.id);assertEquals(-1L,bill.groupId);assertFalse(bill.auto);assertEquals(0,bill.flag)
        assertTrue(bill.detailsJson.contains("自取"));assertFalse(bill.detailsJson.contains("groupId"))
    }
    @Test fun handlesQuotedBracesAndEmptyNonTransactionWithoutRetry() {
        assertEquals("{促销}",requireNotNull(ReceiptResponseParser.parse("""{"money":1,"type":"Expend","shopName":"{促销}"}""")).shopName)
        assertNull(ReceiptResponseParser.parse("```json\n{}\n```"))
        assertNull(ReceiptResponseParser.parse("""{"money":1,"type":"Expend","eventStatus":"PENDING"}"""))
    }
    @Test fun rejectsInvalidAmountsTypesTruncationAndMultipleObjects() {
        for (text in listOf("说明", "{", "$valid\n$valid", """{"money":-1,"type":"Expend"}""", """{"money":1,"type":"UNKNOWN"}""", """{"money":1,"type":"Expend","extra":[]}""")) {
            assertThrows(Exception::class.java) { ReceiptResponseParser.parse(text) }
        }
    }
    @Test fun retriesWithPreviousOutputAndParserErrorThenStopsAtThree() = runBlocking {
        val inputs=mutableListOf<String>()
        val bill=ReceiptRecognition.recognize("原始订单") { input -> inputs.add(input); if(inputs.size==1) "broken output" else valid }
        assertEquals(2,inputs.size);assertTrue(inputs[1].contains("broken output"));assertTrue(inputs[1].contains("解析错误"))
        assertEquals(7.9,requireNotNull(bill).money,0.001)
        var count=0
        try { ReceiptRecognition.recognize("订单") { count++; "broken" }; fail("must fail") }
        catch (e: IllegalArgumentException) { assertEquals(3,count) }
    }
    @Test fun retriesModelErrorsButDoesNotRetryEmptyResultOrCancellation() = runBlocking {
        val inputs=mutableListOf<String>()
        ReceiptRecognition.recognize("原文") { input -> inputs.add(input);if(inputs.size==1) error("HTTP 503 sk-testsecret");valid }
        assertEquals(2,inputs.size);assertTrue(inputs[1].contains("HTTP 503"));assertFalse(inputs[1].contains("sk-testsecret"))
        var count=0
        assertNull(ReceiptRecognition.recognize("无交易") { count++; "{}" });assertEquals(1,count)
        count=0
        try { ReceiptRecognition.recognize("取消") { count++; throw kotlinx.coroutines.CancellationException() };fail("must cancel") }
        catch (e: kotlinx.coroutines.CancellationException) { assertEquals(1,count) }
    }

    @Test fun nonReceiptPageCannotBecomeABillEvenIfModelIncludesAmount() = runBlocking {
        var calls=0
        val result=ReceiptRecognition.recognize("聊天列表", requireReceiptPage=true) {
            calls++; """{"pageType":"other","money":7.9,"type":"Expend","shopName":"群聊"}"""
        }
        assertNull(result); assertEquals(1,calls)
        assertNull(ReceiptResponseParser.parse("{}",requireReceiptPage=true))
    }

    @Test fun ocrRequiresPageClassificationAndRetryCarriesTheMissingFieldError() = runBlocking {
        val inputs=mutableListOf<String>()
        val accepted="""{"pageType":"receipt","money":7.9,"type":"Expend"}"""
        val result=ReceiptRecognition.recognize("单笔付款凭证", requireReceiptPage=true) {
            inputs.add(it); if(inputs.size==1) valid else accepted
        }
        assertEquals(2,inputs.size);assertTrue(inputs[1].contains("pageType"))
        assertEquals(7.9,requireNotNull(result).money,0.001)
        assertFalse(result.detailsJson.contains("pageType"))
        assertNotNull(ReceiptResponseParser.parse(valid)) // Notification schema stays compatible.
        for (kind in listOf("true", "null", "[]", "\"unknown\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReceiptResponseParser.parse("""{"pageType":$kind,"money":1,"type":"Expend"}""",true)
            }
        }
    }
}
