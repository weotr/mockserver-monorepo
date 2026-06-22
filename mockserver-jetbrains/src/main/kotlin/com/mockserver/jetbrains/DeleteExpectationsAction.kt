package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Removes from the running MockServer the expectation(s) declared in the active file,
 * matched by each declared request, via `PUT /mockserver/clear?type=expectations`.
 * Mirrors the VS Code extension's "Delete" CodeLens.
 *
 * The user is asked to confirm first (live state is being deleted); the HTTP calls
 * then run on a background thread (never the EDT) and the result notification is
 * posted back on the EDT.
 */
class DeleteExpectationsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        if (document == null) {
            MockServerNotifier.notify(project, "Open an expectation file in the editor first.", NotificationType.WARNING)
            return
        }
        val definitions = MockServerRestClient.extractRequestDefinitions(document.text)
        if (definitions.isEmpty()) {
            MockServerNotifier.notify(
                project,
                "No request definitions found in this file to delete.",
                NotificationType.INFORMATION
            )
            return
        }
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete these ${definitions.size} expectation(s) from the running MockServer?",
            "Delete Expectations",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) {
            return
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Deleting expectations from MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    for (definition in definitions) {
                        val result = MockServerRestClient.send(
                            MockServerRestClient.buildClearExpectationsRequest(baseUrl, definition)
                        )
                        if (!result.ok) {
                            runOnEdt(project) {
                                MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR)
                            }
                            return
                        }
                    }
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Cleared ${definitions.size} expectation matcher(s) from MockServer.",
                            NotificationType.INFORMATION
                        )
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
