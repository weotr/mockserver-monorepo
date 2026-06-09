package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for OpenDashboardAction — no server or IDE required.
 */
class OpenDashboardActionTest {

    @Test
    fun `default dashboard URL points to localhost 1080`() {
        assertEquals(
            "http://localhost:1080/mockserver/dashboard",
            OpenDashboardAction.DEFAULT_DASHBOARD_URL
        )
    }

    @Test
    fun `action can be instantiated`() {
        val action = OpenDashboardAction()
        assertNotNull(action)
    }
}
