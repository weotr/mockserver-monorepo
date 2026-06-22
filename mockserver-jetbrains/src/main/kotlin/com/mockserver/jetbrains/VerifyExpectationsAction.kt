package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Verifies that every request declared in the active expectation file was received by
 * the running MockServer (each at least once) via `PUT /mockserver/verify`. Reports
 * the first unmet verification's reason, or success when all are satisfied — useful
 * after a test run to confirm the mocked endpoints were actually exercised. Mirrors
 * the VS Code extension's "Verify" CodeLens.
 *
 * The HTTP calls run on a background thread (never the EDT); notifications are posted
 * back on the EDT.
 */
class VerifyExpectationsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        if (document == null) {
            MockServerNotifier.notify(project, "Open an expectation file in the editor first.", NotificationType.WARNING)
            return
        }
        val text = document.text
        val definitions = MockServerRestClient.extractRequestDefinitions(text)
        if (definitions.isEmpty()) {
            MockServerNotifier.notify(
                project,
                "No request definitions found in this file to verify.",
                NotificationType.INFORMATION
            )
            return
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Verifying declared requests against MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    for (definition in definitions) {
                        val result = MockServerRestClient.send(
                            MockServerRestClient.buildVerifyRequest(baseUrl, definition)
                        )
                        // 202 = verified; 406 = not received (the body is the reason).
                        if (result.status == 406) {
                            val reason = result.body.lineSequence().firstOrNull() ?: result.body
                            runOnEdt(project) {
                                MockServerNotifier.notify(
                                    project,
                                    "A declared request was not received — $reason",
                                    NotificationType.WARNING
                                )
                            }
                            return
                        }
                        if (result.status != 202) {
                            runOnEdt(project) {
                                MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR)
                            }
                            return
                        }
                    }
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Verified: all ${definitions.size} declared request(s) were received.",
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
