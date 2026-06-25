package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Takes the OpenAPI/Swagger spec in the active editor and asks the running MockServer
 * to generate expectations from it via `PUT /mockserver/openapi`. A JSON spec is sent
 * as an embedded object; YAML (or anything that is not JSON) is sent as a string. The
 * generated expectations array (HTTP 201 body) is opened in a new JSON editor tab.
 *
 * The HTTP call runs on a background thread; the editor/notification UI runs on the EDT.
 */
class GenerateFromOpenApiAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        if (document == null) {
            MockServerNotifier.notify(project, "Open an OpenAPI/Swagger spec in the editor first.", NotificationType.WARNING)
            return
        }
        val specText = document.text
        if (specText.isBlank()) {
            MockServerNotifier.notify(project, "The active editor file is empty.", NotificationType.WARNING)
            return
        }
        if (!MockServerRestClient.looksLikeOpenApiSpec(specText)) {
            MockServerNotifier.notify(
                project,
                "The active editor doesn't look like an OpenAPI/Swagger spec (no top-level \"openapi\" or " +
                    "\"swagger\" field). Open your OpenAPI/Swagger spec file and run this action again.",
                NotificationType.WARNING
            )
            return
        }
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Generating expectations from OpenAPI spec", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildGenerateFromOpenApiRequest(baseUrl, specText)
                    )
                    if (!result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    if (MockServerRestClient.isEmptyExpectationsBody(result.body)) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer generated no expectations from the spec.", NotificationType.INFORMATION) }
                        return
                    }
                    val pretty = MockServerRestClient.prettyPrintJson(result.body)
                    runOnEdt(project) { MockServerEditors.openJsonInEditor(project, "openapi-expectations.mockserver.json", pretty) }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
