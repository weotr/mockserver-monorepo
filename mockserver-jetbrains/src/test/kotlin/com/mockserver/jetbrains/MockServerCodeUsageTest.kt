package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the best-effort MockServer-usage detection behind the gutter markers. */
class MockServerCodeUsageTest {

    @Test
    fun `detects MockServerClient construction and usage`() {
        assertEquals(MockServerCodeUsage.Marker.CLIENT, MockServerCodeUsage.detect("new MockServerClient(\"localhost\", 1080)"))
        assertEquals(MockServerCodeUsage.Marker.CLIENT, MockServerCodeUsage.detect("mockServerClient.when(request())"))
    }

    @Test
    fun `detects the JUnit 5 and Spring annotations and Testcontainers`() {
        assertEquals(MockServerCodeUsage.Marker.SETTINGS, MockServerCodeUsage.detect("@MockServerSettings(ports = {1080})"))
        assertEquals(MockServerCodeUsage.Marker.SPRING_TEST, MockServerCodeUsage.detect("@MockServerTest"))
        assertEquals(MockServerCodeUsage.Marker.TESTCONTAINERS, MockServerCodeUsage.detect("MockServerContainer container = new MockServerContainer(IMAGE);"))
    }

    @Test
    fun `does not match unrelated identifiers`() {
        assertNull(MockServerCodeUsage.detect("MyMockServerClientWrapper wrapper;"))
        assertNull(MockServerCodeUsage.detect("// just a comment about servers"))
        assertFalse(MockServerCodeUsage.matches("HttpClient client = new HttpClient();"))
    }

    @Test
    fun `annotation wins over bare client when both present on one line`() {
        // @MockServerSettings has a longer, more specific pattern than MockServerClient.
        assertEquals(MockServerCodeUsage.Marker.SETTINGS, MockServerCodeUsage.detect("@MockServerSettings // uses MockServerClient internally"))
    }
}
