package com.mockserver.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandlerFactory
import java.util.concurrent.TimeUnit

/**
 * Shared `docker run` launch logic, used by both the Tools-menu action and the
 * tool-window button so the command is built from settings in exactly one place.
 */
object MockServerDocker {

    /** Build the `docker run` command line from the current settings. */
    fun startCommand(settings: MockServerSettings = MockServerSettings.getInstance()): GeneralCommandLine =
        GeneralCommandLine(
            "docker", "run", "-d", "--rm",
            "--name", settings.effectiveContainerName(),
            // MockServer always listens on 1080 inside the container; map the
            // configured host port to it.
            "-p", "${settings.effectivePort()}:1080",
            settings.effectiveImage()
        )

    /** Build the `docker info` command used to probe daemon availability. */
    fun infoCommand(): GeneralCommandLine = GeneralCommandLine("docker", "info")

    /**
     * Probe whether the Docker daemon is reachable by running `docker info` and
     * checking the exit code (mirrors the VS Code extension's pre-flight check).
     * Returns `true` only when the command runs and exits `0`; any launch failure
     * (Docker not installed) or non-zero exit (daemon not running) yields `false`.
     *
     * BLOCKING: runs an external process and waits — must not be called on the EDT.
     */
    fun isDockerAvailable(): Boolean =
        try {
            val process = infoCommand().createProcess()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }

    /** Start the container in the background. Throws if Docker is unavailable. */
    fun start(settings: MockServerSettings = MockServerSettings.getInstance()) {
        ProcessHandlerFactory.getInstance()
            .createProcessHandler(startCommand(settings))
            .startNotify()
    }
}
