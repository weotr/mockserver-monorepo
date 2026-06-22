package com.mockserver.jetbrains

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Phase 4 drift quick-fix, realised as an [IntentionAction] (Community-API safe — no
 * Ultimate-only annotator inspection framework needed) available on any
 * `*.mockserver.json` file. Invoking it (Alt+Enter → "Check MockServer drift for this
 * file's expectations") fetches `GET /mockserver/drift`, intersects the records with the
 * expectation `id`s declared in the open file via [MockServerDriftMapping], and opens a
 * focused per-expectation report — "upstream now returns X your stub omits" mapped back
 * to *your* expectations, instead of an untraceable global JSON list.
 *
 * The network call runs on a background [Task.Backgroundable]; results are marshalled
 * back onto the EDT. The file text is snapshotted on the EDT before the call so the
 * background thread never touches PSI.
 */
class DriftQuickFixIntention : IntentionAction {

    override fun getText(): String = "Check MockServer drift for this file's expectations"

    override fun getFamilyName(): String = "MockServer"

    override fun startInWriteAction(): Boolean = false

    /** Offered only on `*.mockserver.json(c)` files (the schema-validated expectation files). */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val name = file?.name ?: return false
        return name.endsWith(".mockserver.json") || name.endsWith(".mockserver.jsonc")
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val fileText = file?.text ?: return
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        // Bail early (on the EDT) when the file declares no id-keyed expectations: drift
        // is keyed by expectation id, so id-less stubs can never be mapped.
        if (MockServerDriftMapping.expectationIds(fileText).isEmpty()) {
            MockServerNotifier.notify(
                project,
                "This file declares no expectations with an \"id\" — drift is matched by expectation id, " +
                    "so add an \"id\" to the expectations you want drift-checked.",
                NotificationType.INFORMATION
            )
            return
        }

        object : Task.Backgroundable(project, "Checking MockServer drift for this file", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(MockServerRestClient.buildRetrieveDriftRequest(baseUrl))
                    if (!result.ok) {
                        runOnEdt(project) {
                            MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR)
                        }
                        return
                    }
                    val fileDrifts = MockServerDriftMapping.driftForFile(result.body, fileText)
                    if (fileDrifts.isEmpty()) {
                        runOnEdt(project) {
                            MockServerNotifier.notify(
                                project,
                                "No recorded drift affects the expectations declared in this file.",
                                NotificationType.INFORMATION
                            )
                        }
                        return
                    }
                    val report = MockServerDriftMapping.formatFileDrift(fileDrifts)
                    runOnEdt(project) { MockServerEditors.openTextInEditor(project, "mockserver-file-drift.txt", report) }
                } catch (ex: Exception) {
                    runOnEdt(project) {
                        MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR)
                    }
                }
            }
        }.queue()
    }

    companion object {
        /** Allow construction off the registered EP for unit testing of availability. */
        @JvmStatic
        fun create(): DriftQuickFixIntention = DriftQuickFixIntention()
    }
}
