package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Retrieves MockServer's mock-drift records via `GET /mockserver/drift` and opens a
 * readable text report in a new editor tab. Drift is recorded when MockServer proxies
 * traffic to a real upstream and a matching stub differs. If no drift has been recorded
 * an info notification is shown instead.
 *
 * The HTTP call runs on a background thread; opening the editor and posting
 * notifications happen back on the EDT.
 */
class ShowDriftReportAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Retrieving drift report from MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildRetrieveDriftRequest(baseUrl)
                    )
                    if (!result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    val report = MockServerRestClient.formatDriftReport(result.body)
                    if (report.empty) {
                        runOnEdt(project) {
                            MockServerNotifier.notify(
                                project,
                                "No drift detected. Drift is recorded when MockServer proxies traffic to a real upstream and a matching stub differs.",
                                NotificationType.INFORMATION
                            )
                        }
                        return
                    }
                    runOnEdt(project) { MockServerEditors.openTextInEditor(project, "mockserver-drift.txt", report.report) }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
