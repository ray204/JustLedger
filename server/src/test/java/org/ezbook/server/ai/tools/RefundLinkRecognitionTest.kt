package org.ezbook.server.ai.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.tools.RefundMatchPolicy

class RefundLinkRecognitionTest {
    private val refund=BillInfoModel(id=9,money=20.0,type=BillType.IncomeRefund,shopName="咖啡店")
    private val choices=listOf(RefundMatchPolicy.Candidate(BillInfoModel(id=1,money=80.0,shopName="咖啡店A",time=1000),60.0),
        RefundMatchPolicy.Candidate(BillInfoModel(id=2,money=90.0,shopName="咖啡店B",time=2000),50.0))
    @Test fun acceptsBareAndFencedIndexAndDoesNotRetryNoMatch()= runBlocking {
        for (response in listOf("{\"index\":2}","```json\n{\"index\":2}\n```")) {
            assertEquals(2L,RefundLinkRecognition.pick(refund,choices) { response }?.id)
        }
        var calls=0
        assertNull(RefundLinkRecognition.pick(refund,choices) { calls++;"{\"index\":0}" })
        assertEquals(1,calls)
    }
    @Test fun invalidIndexesRetryWithPreviousOutputAndErrorAndStopAtThree()= runBlocking {
        val prompts=mutableListOf<String>()
        assertEquals(1L,RefundLinkRecognition.pick(refund,choices) {
            prompts.add(it);if(prompts.size==1) "{\"index\":99}" else "{\"index\":1}"
        }?.id)
        assertTrue(prompts[1].contains("99"));assertTrue(prompts[1].contains("超出候选范围"))
        var calls=0
        try { RefundLinkRecognition.pick(refund,choices) { calls++;"{\"index\":1.5}" };fail() }
        catch (e:IllegalArgumentException) { assertEquals(3,calls) }
    }
    @Test fun identicalCandidatesAndCancellationCannotCreateAnAutomaticSelection()= runBlocking {
        val duplicated=listOf(choices[0],choices[0].copy(bill=choices[0].bill.copy(id=3)))
        assertNull(RefundLinkRecognition.pick(refund,duplicated) { "{\"index\":1}" })
        var calls=0
        try { RefundLinkRecognition.pick(refund,choices) { calls++;throw kotlinx.coroutines.CancellationException() };fail() }
        catch (e:kotlinx.coroutines.CancellationException) { assertEquals(1,calls) }
        assertNull(RefundLinkRecognition.pick(refund,emptyList()) { error("must not call") })
    }
}
