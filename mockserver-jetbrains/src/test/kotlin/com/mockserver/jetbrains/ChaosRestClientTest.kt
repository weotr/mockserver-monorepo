package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import kotlin.test.assertEquals

/** Unit tests for the chaos-experiment control-plane request builders (Phase 6). */
class ChaosRestClientTest {

    private fun bodyOf(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElse(null) ?: return ""
        val sb = StringBuilder()
        publisher.subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(item: java.nio.ByteBuffer) { sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item)) }
            override fun onError(t: Throwable) {}
            override fun onComplete() {}
        })
        return sb.toString()
    }

    @Test
    fun `chaos status GETs the chaosExperiment endpoint`() {
        val req = MockServerRestClient.buildChaosStatusRequest("http://localhost:1080")
        assertEquals("GET", req.method())
        assertEquals("http://localhost:1080/mockserver/chaosExperiment", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Accept").orElse(""))
    }

    @Test
    fun `chaos start PUTs the definition verbatim`() {
        val def = """{ "name": "exp", "stages": [] }"""
        val req = MockServerRestClient.buildChaosStartRequest("http://localhost:1080", def)
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/chaosExperiment", req.uri().toString())
        assertEquals(def, bodyOf(req))
    }

    @Test
    fun `chaos stop DELETEs the chaosExperiment endpoint`() {
        val req = MockServerRestClient.buildChaosStopRequest("http://localhost:1080")
        assertEquals("DELETE", req.method())
        assertEquals("http://localhost:1080/mockserver/chaosExperiment", req.uri().toString())
    }
}
