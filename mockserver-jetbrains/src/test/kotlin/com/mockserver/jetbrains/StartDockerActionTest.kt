package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for StartDockerAction constants — no Docker or IDE required.
 */
class StartDockerActionTest {

    @Test
    fun `default port is 1080`() {
        assertEquals(1080, StartDockerAction.DEFAULT_PORT)
    }

    @Test
    fun `image name is mockserver`() {
        assertEquals("mockserver/mockserver", StartDockerAction.IMAGE)
    }

    @Test
    fun `version matches project version`() {
        assertEquals("7.0.0", StartDockerAction.MOCKSERVER_VERSION)
    }

    @Test
    fun `container name is set`() {
        assertEquals("mockserver-ide", StartDockerAction.CONTAINER_NAME)
    }

    @Test
    fun `action can be instantiated`() {
        val action = StartDockerAction()
        assertNotNull(action)
    }
}
