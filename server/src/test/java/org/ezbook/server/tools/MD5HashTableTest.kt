package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MD5HashTableTest {
    @Test fun reservationUsesTheSameRawKeyForLookupAndRelease() {
        val cache = MD5HashTable()
        assertTrue(cache.claim("app|NOTICE|event-1"))
        assertFalse(cache.claim("app|NOTICE|event-1"))
        cache.remove("app|NOTICE|event-1")
        assertTrue(cache.claim("app|NOTICE|event-1"))
    }
    @Test fun onlyOneConcurrentCaptureClaimsAnEvent() {
        val cache = MD5HashTable()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = (1..8).map { pool.submit<Boolean> { start.await(); cache.claim("same-event") } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
    }
    @Test fun expirationBoundaryAndDistinctSourcesRemainIndependent() {
        var now = 100L
        val cache = MD5HashTable(60) { now }
        assertTrue(cache.claim("sms|1"))
        assertTrue(cache.claim("notification|1"))
        now = 160
        assertFalse(cache.claim("sms|1"))
        now = 161
        assertTrue(cache.claim("sms|1"))
    }
}
