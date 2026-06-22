package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the settings model and the docker command builder — no Docker
 * or IDE required. A MockServerSettings is constructed directly (not via the
 * application service) so these run as plain JUnit tests.
 */
class StartDockerActionTest {

    private fun settings(image: String = "", container: String = "", port: Int = 1080): MockServerSettings {
        val s = MockServerSettings()
        s.loadState(MockServerSettings.State().also {
            it.dockerImage = image
            it.containerName = container.ifBlank { MockServerSettings.DEFAULT_CONTAINER_NAME }
            it.port = port
        })
        return s
    }

    @Test
    fun `default port is 1080`() {
        assertEquals(1080, settings().effectivePort())
    }

    @Test
    fun `default container name is mockserver-ide`() {
        assertEquals("mockserver-ide", settings().effectiveContainerName())
    }

    @Test
    fun `blank image derives the mockserver image (no hardcoded version)`() {
        assertTrue(settings(image = "").effectiveImage().startsWith("mockserver/mockserver:"))
    }

    @Test
    fun `explicit image override is used verbatim`() {
        assertEquals("mockserver/mockserver:7.2.0", settings(image = "mockserver/mockserver:7.2.0").effectiveImage())
    }

    @Test
    fun `invalid port falls back to default`() {
        assertEquals(1080, settings(port = 0).effectivePort())
        assertEquals(1080, settings(port = 70000).effectivePort())
    }

    @Test
    fun `docker command maps the configured host port to container 1080`() {
        val cmd = MockServerDocker.startCommand(settings(port = 2080))
        val args = cmd.commandLineString
        assertTrue(args.contains("2080:1080"), "expected host-port mapping in: $args")
    }

    @Test
    fun `dashboard url uses the configured port`() {
        assertEquals("http://localhost:2080/mockserver/dashboard", settings(port = 2080).dashboardUrl())
    }

    @Test
    fun `action can be instantiated`() {
        assertNotNull(StartDockerAction())
    }
}
