package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Resets the running MockServer via `PUT /mockserver/reset`, clearing all
 * expectations and recorded logs.
 *
 * The user is asked to confirm first (the reset is irreversible); the HTTP call
 * then runs on a background thread (never the EDT) and the result notification is
 * posted back on the EDT.
 */
class ResetAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Reset MockServer? This clears all expectations and logs.",
            "Reset MockServer",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) {
            return
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Resetting MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildResetRequest(baseUrl)
                    )
                    if (result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer reset (HTTP ${result.status}).", NotificationType.INFORMATION) }
                    } else {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
