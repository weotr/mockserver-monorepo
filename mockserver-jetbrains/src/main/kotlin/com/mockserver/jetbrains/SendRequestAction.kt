package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Sends the ad-hoc HTTP request described in the active editor at the running
 * MockServer and opens the response in a new editor tab. The editor must hold a
 * JSON request spec of the shape
 * `{ "method": "GET", "path": "/api/x", "headers": { "K": "V" }, "body": "..." }`
 * (method and path required; headers and body optional). This mirrors the VS Code
 * extension's "Send Test Request" feature.
 *
 * The HTTP call runs on a background thread (never the EDT); the response editor
 * tab and any notification are opened back on the EDT.
 */
class SendRequestAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        if (document == null) {
            MockServerNotifier.notify(
                project,
                "Open a request file in the editor first " +
                    "(a JSON object with \"method\" and \"path\").",
                NotificationType.WARNING
            )
            return
        }
        val spec = try {
            MockServerRestClient.parseRequestSpec(document.text)
        } catch (ex: IllegalArgumentException) {
            MockServerNotifier.notify(project, ex.message ?: "Invalid request spec.", NotificationType.WARNING)
            return
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Sending request to MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildScratchRequest(baseUrl, spec)
                    )
                    // Also ask the server whether the request matched a registered
                    // expectation (and the nearest miss). Best-effort: a server without
                    // the debugMismatch endpoint must not break the response view.
                    val analysisHeader = try {
                        val debug = MockServerRestClient.send(
                            MockServerRestClient.buildDebugMismatchRequest(
                                baseUrl,
                                MockServerRestClient.requestSpecToDefinitionJson(spec)
                            )
                        )
                        if (debug.ok) {
                            MockServerRestClient.formatMatchAnalysis(
                                MockServerRestClient.parseMatchAnalysis(debug.body)
                            ) + "\n\n"
                        } else {
                            ""
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    val body = MockServerRestClient.prettyPrintJson(result.body)
                    val summary = "${analysisHeader}HTTP ${result.status}\n\n$body"
                    runOnEdt(project) { MockServerEditors.openTextInEditor(project, "mockserver-response.txt", summary) }
                } catch (ex: Exception) {
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Failed to reach MockServer at $baseUrl: ${ex.message}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        }.queue()
    }
}
