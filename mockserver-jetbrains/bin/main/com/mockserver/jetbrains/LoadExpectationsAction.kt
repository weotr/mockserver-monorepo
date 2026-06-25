package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Reads the text of the file in the active editor and loads it into the running
 * MockServer via `PUT /mockserver/expectation`. The body is sent as-is, so either
 * a single expectation object or an array of expectations is accepted.
 *
 * The HTTP call runs on a background thread (never the EDT); the result
 * notification is posted back on the EDT.
 */
class LoadExpectationsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        if (document == null) {
            MockServerNotifier.notify(project, "Open an expectation file in the editor first.", NotificationType.WARNING)
            return
        }
        val text = document.text
        if (text.isBlank()) {
            MockServerNotifier.notify(project, "The active editor file is empty.", NotificationType.WARNING)
            return
        }
        if (!MockServerRestClient.isJsonObjectOrArray(text)) {
            MockServerNotifier.notify(
                project,
                "The active editor isn't valid JSON. Open a MockServer expectation file " +
                    "(a single expectation object, or an array of expectations).",
                NotificationType.WARNING
            )
            return
        }
        if (MockServerRestClient.looksLikeOpenApiSpec(text)) {
            MockServerNotifier.notify(
                project,
                "This looks like an OpenAPI/Swagger spec, not an expectation. " +
                    "Use \"Generate Expectations From OpenAPI Spec\" instead.",
                NotificationType.WARNING
            )
            return
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Loading expectations into MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildLoadExpectationsRequest(baseUrl, text)
                    )
                    if (result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "Loaded expectations into MockServer (HTTP ${result.status}).", NotificationType.INFORMATION) }
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
