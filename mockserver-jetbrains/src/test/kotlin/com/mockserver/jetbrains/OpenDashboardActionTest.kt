package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for OpenDashboardAction / dashboard URL — no server or IDE required.
 */
class OpenDashboardActionTest {

    @Test
    fun `default dashboard URL points to localhost 1080`() {
        val s = MockServerSettings()
        assertEquals("http://localhost:1080/mockserver/dashboard", s.dashboardUrl())
    }

    @Test
    fun `action can be instantiated`() {
        assertNotNull(OpenDashboardAction())
    }
}
