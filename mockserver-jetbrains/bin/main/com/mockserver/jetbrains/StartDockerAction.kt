package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Action that starts a MockServer Docker container using the configured image,
 * container name, and port (see [MockServerSettings]).
 *
 * Both the Docker-availability probe and the `docker run` launch block on an
 * external process, so the work runs on a [Task.Backgroundable] thread and the
 * resulting notification is posted back on the EDT via [runOnEdt]. Mirrors the VS
 * Code extension, which runs `docker info` first and surfaces a clear "Docker is
 * not running" message before attempting to start the container.
 */
class StartDockerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = MockServerSettings.getInstance()
        val image = settings.effectiveImage()
        val port = settings.effectivePort()

        object : Task.Backgroundable(project, "Starting MockServer container", false) {
            override fun run(indicator: ProgressIndicator) {
                if (!MockServerDocker.isDockerAvailable()) {
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Docker is not running or not installed. Start Docker and try again.",
                            NotificationType.ERROR
                        )
                    }
                    return
                }
                try {
                    MockServerDocker.start(settings)
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Requested MockServer ($image) on port $port. It should be reachable shortly.",
                            NotificationType.INFORMATION
                        )
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Failed to start MockServer: ${ex.message}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        }.queue()
    }
}
