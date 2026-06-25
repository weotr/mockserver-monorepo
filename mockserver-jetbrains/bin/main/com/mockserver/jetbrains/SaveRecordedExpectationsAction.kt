package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Retrieves the expectations MockServer recorded from proxied/forwarded traffic via
 * `PUT /mockserver/retrieve?type=recorded_expectations&format=<format>` and opens
 * them in a new editor tab. The user first chooses the output format — JSON (opened
 * as a `.mockserver.json` tab) or the Java DSL (opened as a plain-text `.java` tab) —
 * mirroring the VS Code extension's "Save Recorded Expectations" command. If nothing
 * has been recorded yet (blank or `[]`) an info notification is shown instead.
 *
 * The format chooser runs on the EDT; the HTTP call runs on a background thread, with
 * opening the editor and posting notifications marshalled back onto the EDT.
 */
class SaveRecordedExpectationsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // showDialog returns the index of the chosen option, or -1 if cancelled.
        val choice = Messages.showDialog(
            project,
            "Save the recorded expectations as JSON or as a Java DSL snippet?",
            "Save Recorded Expectations",
            arrayOf("JSON", "Java"),
            0,
            Messages.getQuestionIcon()
        )
        if (choice < 0) {
            return
        }
        val format = if (choice == 1) "java" else "json"

        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Retrieving recorded expectations from MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildRetrieveRecordedRequest(baseUrl, format)
                    )
                    if (!result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    if (MockServerRestClient.isEmptyExpectationsBody(result.body)) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer has not recorded any expectations yet.", NotificationType.INFORMATION) }
                        return
                    }
                    if (format == "java") {
                        runOnEdt(project) { MockServerEditors.openTextInEditor(project, "RecordedExpectations.java", result.body) }
                    } else {
                        val pretty = MockServerRestClient.prettyPrintJson(result.body)
                        runOnEdt(project) { MockServerEditors.openJsonInEditor(project, "recorded-expectations.mockserver.json", pretty) }
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
