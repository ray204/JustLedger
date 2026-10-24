package org.ezbook.server.tools
import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.ai.providers.OpenAIEndpoint

class ReceiptDetailsTest {
    @Test fun fillsNestedMissingFactsWithoutOverwritingExistingValuesOrArrays() {
        val merged=ReceiptDetails.read(ReceiptDetails.merge("""{"数量":1,"规格":{"杯型":"大杯"},"明细":["已有商品"]}""", """{"数量":2,"规格":{"杯型":"小杯","温度":"冰"},"明细":["另一商品"],"优惠":12.1}"""))
        assertEquals(1,merged["数量"].asInt);assertEquals("大杯",merged["规格"].asJsonObject["杯型"].asString)
        assertEquals("冰",merged["规格"].asJsonObject["温度"].asString);assertEquals("已有商品",merged["明细"].asJsonArray[0].asString)
        assertEquals(12.1,merged["优惠"].asDouble,0.001)
        assertTrue(ReceiptDetails.lines(merged.toString()).any { it.contains("温度：冰") })
    }
    @Test fun acceptsBaseUrlWithOrWithoutV1ExactlyOnce() {
        for (base in listOf("https://example.test","https://example.test/","https://example.test/v1","https://example.test/v1/"))
            assertEquals("https://example.test/v1/chat/completions",OpenAIEndpoint.url(base,"chat/completions"))
    }
}
