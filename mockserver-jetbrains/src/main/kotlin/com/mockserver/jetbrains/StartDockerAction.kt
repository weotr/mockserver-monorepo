package com.mockserver.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Action that starts a MockServer Docker container.
 * Runs: docker run -d --rm -p 1080:1080 mockserver/mockserver:<version>
 */
class StartDockerAction : AnAction() {

    companion object {
        const val MOCKSERVER_VERSION = "7.0.0"
        const val DEFAULT_PORT = 1080
        const val CONTAINER_NAME = "mockserver-ide"
        const val IMAGE = "mockserver/mockserver"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val commandLine = GeneralCommandLine(
            "docker", "run",
            "-d",
            "--rm",
            "--name", CONTAINER_NAME,
            "-p", "$DEFAULT_PORT:$DEFAULT_PORT",
            "$IMAGE:$MOCKSERVER_VERSION"
        )

        try {
            val handler: OSProcessHandler = ProcessHandlerFactory.getInstance()
                .createProcessHandler(commandLine)
            handler.startNotify()
            notify(project, "MockServer container starting on port $DEFAULT_PORT", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            notify(
                project,
                "Failed to start MockServer: ${ex.message}",
                NotificationType.ERROR
            )
        }
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MockServer Notifications")
            .createNotification(content, type)
            .notify(project)
    }
}
