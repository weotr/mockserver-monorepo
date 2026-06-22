package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * Lists the WASM custom-rule modules registered on the running MockServer via
 * `GET /mockserver/wasm/modules` and opens the JSON array of module names in a new
 * editor tab. If no modules are registered (empty `[]`) an info notification is
 * shown instead.
 *
 * The HTTP call runs on a background thread; opening the editor and posting
 * notifications happen back on the EDT.
 */
class ListWasmModulesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Listing WASM modules from MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildListWasmRequest(baseUrl)
                    )
                    if (!result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    if (MockServerRestClient.isEmptyExpectationsBody(result.body)) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "No WASM modules are registered on MockServer.", NotificationType.INFORMATION) }
                        return
                    }
                    val pretty = MockServerRestClient.prettyPrintJson(result.body)
                    runOnEdt(project) { MockServerEditors.openTextInEditor(project, "wasm-modules.mockserver.json", pretty) }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
