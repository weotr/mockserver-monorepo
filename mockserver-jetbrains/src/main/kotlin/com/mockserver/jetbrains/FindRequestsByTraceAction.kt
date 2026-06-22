package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Distributed-trace correlation. Prompts for a W3C trace id (32 hex) or a full
 * `traceparent` header value, retrieves the requests MockServer has received via
 * `PUT /mockserver/retrieve?type=requests&format=json`, filters them down to those
 * carrying a `traceparent` header with that trace id, and opens the matching
 * requests as JSON in a new editor tab. If nothing matches an info notification is
 * shown instead.
 *
 * The input dialog and notifications run on the EDT; the HTTP call runs on a
 * background thread, marshalling all UI work back onto the EDT.
 */
class FindRequestsByTraceAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val input = Messages.showInputDialog(
            project,
            "Trace id (32 hex) or full W3C traceparent:",
            "Find Requests by Trace",
            null
        )
        if (input.isNullOrBlank()) {
            return
        }

        val traceId = MockServerRestClient.extractTraceId(input)
        if (traceId == null) {
            MockServerNotifier.notify(
                project,
                "\"$input\" is not a valid W3C trace id (32 hex) or traceparent (version-traceId-parentId-flags).",
                NotificationType.WARNING
            )
            return
        }

        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Finding requests for trace $traceId", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildRetrieveRequestsRequest(baseUrl)
                    )
                    if (!result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    val filtered = MockServerRestClient.filterRequestsByTrace(result.body, input)
                    if (filtered.matchesJson.trim() == "[]") {
                        runOnEdt(project) {
                            MockServerNotifier.notify(
                                project,
                                "No requests found for trace $traceId.",
                                NotificationType.INFORMATION
                            )
                        }
                        return
                    }
                    val shortId = traceId.take(8)
                    runOnEdt(project) { MockServerEditors.openJsonInEditor(project, "trace-$shortId.json", filtered.matchesJson) }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
