package net.ankio.auto.update

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdateFeedTest {
    private fun response(code: Int, body: String, block: (UpdateFeed) -> Unit) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        server.start()
        try { block(UpdateFeed(OkHttpClient(), server.url("/release").toString())) }
        finally { server.shutdown() }
    }
    @Test fun currentVersionIsDifferentFromNetworkRateLimitAndMalformedFeedFailures() {
        response(200, """{"tag_name":"v0.1.2","draft":false,"prerelease":false}""") { assertNull(it.latest("0.1.2")) }
        for (code in listOf(403, 429, 404, 500)) response(code, "service error") {
            assertThrows(UpdateFailure::class.java) { it.latest("0.1.2") }
        }
        response(200, "invalid JSON") { assertThrows(UpdateFailure::class.java) { it.latest("0.1.2") } }
        response(200, " ".repeat(1024 * 1024 + 1)) { assertThrows(UpdateFailure::class.java) { it.latest("0.1.2") } }
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val unavailable = UpdateFeed(OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build(), "http://127.0.0.1:$closed/release")
        assertThrows(UpdateFailure::class.java) { unavailable.latest("0.1.2") }
    }
}
