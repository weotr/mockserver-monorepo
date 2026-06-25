package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/**
 * Runs an OpenAPI **contract / resiliency test** of a live service against the
 * OpenAPI/Swagger spec in the active editor, via `PUT /mockserver/contractTest`, and
 * opens a per-operation pass/fail report in a new editor tab. The JetBrains parity of
 * the VS Code extension's `mockserver.contractTest` command.
 *
 * Wired into the editor/project-view context menu on `.yaml`/`.yml`/`.json` files
 * (it self-guards with [MockServerRestClient.looksLikeOpenApiSpec], so a non-spec
 * file yields a clear warning instead of a raw 400). The user is prompted for the
 * base URL of the service under test. The HTTP call runs on a background thread.
 */
class ContractTestAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Offer the action only when there is an editable document to read the spec from.
        val hasEditor = e.getData(CommonDataKeys.EDITOR)?.document != null
        e.presentation.isEnabledAndVisible = e.project != null && hasEditor
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        if (document == null) {
            MockServerNotifier.notify(project, "Open an OpenAPI/Swagger spec in the editor first.", NotificationType.WARNING)
            return
        }
        val specText = document.text
        if (!MockServerRestClient.looksLikeOpenApiSpec(specText)) {
            MockServerNotifier.notify(
                project,
                "The active editor doesn't look like an OpenAPI/Swagger spec (no top-level \"openapi\" or " +
                    "\"swagger\" field). Open your spec and run this action again.",
                NotificationType.WARNING
            )
            return
        }

        val serviceUrl = Messages.showInputDialog(
            project,
            "Base URL of the service under test:",
            "Contract Test",
            Messages.getQuestionIcon(),
            "http://localhost:8080",
            object : InputValidator {
                override fun checkInput(input: String?): Boolean =
                    input != null && Regex("^https?://").containsMatchIn(input.trim())

                override fun canClose(input: String?): Boolean = checkInput(input)
            }
        )?.trim() ?: return

        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Running MockServer contract test", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildContractTestRequest(baseUrl, specText, serviceUrl)
                    )
                    if (!result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    val report = MockServerRestClient.parseContractTestReport(result.body)
                    val text = MockServerRestClient.formatContractTestReport(report)
                    runOnEdt(project) { MockServerEditors.openTextInEditor(project, "contract-test.txt", text) }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Contract test failed — could not reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
