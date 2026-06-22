package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the contract/resiliency runner (JB2): the
 * [MockServerRestClient.buildContractTestRequest] request builder and the
 * [MockServerRestClient.parseContractTestReport] / `formatContractTestReport`
 * report helpers. IDE-free.
 */
class ContractTestRestClientTest {

    private fun bodyOf(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
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
    fun `request PUTs spec as a JSON string with baseUrl and optional operationId`() {
        val spec = """{ "openapi": "3.0.0", "paths": {} }"""
        val req = MockServerRestClient.buildContractTestRequest(
            "http://localhost:1080",
            spec,
            "http://localhost:8080",
            "getThing",
        )
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/contractTest", req.uri().toString())
        val body = JsonParser.parseString(bodyOf(req)).asJsonObject
        // spec MUST be a JSON string (the server reads it with JsonNode.asText(), which
        // returns "" for an object node). The raw spec text round-trips through it.
        assertTrue(body.get("spec").isJsonPrimitive)
        assertEquals(spec, body.get("spec").asString)
        assertTrue(body.get("spec").asString.contains("\"openapi\""))
        assertEquals("http://localhost:8080", body.get("baseUrl").asString)
        assertEquals("getThing", body.get("operationId").asString)
    }

    @Test
    fun `yaml or url spec is sent as a string and no operationId is omitted`() {
        val req = MockServerRestClient.buildContractTestRequest(
            "http://localhost:1080", "https://example.com/openapi.yaml", "http://svc"
        )
        val body = JsonParser.parseString(bodyOf(req)).asJsonObject
        assertTrue(body.get("spec").isJsonPrimitive)
        assertEquals("https://example.com/openapi.yaml", body.get("spec").asString)
        assertFalse(body.has("operationId"))
    }

    @Test
    fun `parse and format a per-operation report`() {
        val report = MockServerRestClient.parseContractTestReport(
            """
            {
              "baseUrl": "http://svc",
              "totalOperations": 2,
              "passed": 1,
              "failed": 1,
              "allPassed": false,
              "results": [
                { "operationId": "ok", "method": "GET", "path": "/a", "statusCodeReceived": 200, "passed": true, "validationErrors": [] },
                { "operationId": "bad", "method": "POST", "path": "/b", "statusCodeReceived": 500, "passed": false, "validationErrors": ["status 500 not in spec", "body schema mismatch"] }
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, report.totalOperations)
        assertEquals(1, report.passed)
        assertEquals(1, report.failed)
        assertFalse(report.allPassed)

        val text = MockServerRestClient.formatContractTestReport(report)
        assertTrue(text.contains("1/2 passed"))
        assertTrue(text.contains("[PASS] GET /a [ok] -> HTTP 200"))
        assertTrue(text.contains("[FAIL] POST /b [bad] -> HTTP 500"))
        assertTrue(text.contains("    - status 500 not in spec"))
        assertTrue(text.contains("    - body schema mismatch"))
    }

    @Test
    fun `report derives counts when the server omits them`() {
        val report = MockServerRestClient.parseContractTestReport(
            """
            { "baseUrl": "http://svc", "results": [
                { "operationId": "a", "method": "GET", "path": "/a", "statusCodeReceived": 200, "passed": true },
                { "operationId": "b", "method": "GET", "path": "/b", "statusCodeReceived": 200, "passed": true }
            ] }
            """.trimIndent()
        )
        assertEquals(2, report.totalOperations)
        assertEquals(2, report.passed)
        assertEquals(0, report.failed)
        assertTrue(report.allPassed)
    }

    @Test
    fun `non-json body yields an empty report`() {
        val report = MockServerRestClient.parseContractTestReport("not json")
        assertEquals(0, report.totalOperations)
        assertTrue(report.results.isEmpty())
    }
}
