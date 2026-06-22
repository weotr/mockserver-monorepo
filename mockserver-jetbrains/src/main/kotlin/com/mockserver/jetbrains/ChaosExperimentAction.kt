package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Phase 6 chaos panel (as an action): drive the chaos-experiment control plane
 * (`GET/PUT/DELETE /mockserver/chaosExperiment`) from the IDE.
 *
 * The user picks one of:
 * - **Show status** — `GET` the current experiment status and open it as a JSON tab
 *   (stage index, remaining ms, auto-halt state), or notify when none has run;
 * - **Start from this file** — `PUT` the active editor's experiment definition JSON
 *   (`{ name, stages: [...] }`) and report the resulting status (or the 400 reason);
 * - **Stop** — `DELETE` the running experiment and clear all chaos.
 *
 * Network I/O runs on a background thread; results are marshalled back onto the EDT.
 */
class ChaosExperimentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        val choice = Messages.showDialog(
            project,
            "Drive the MockServer chaos experiment control plane:",
            "Chaos Experiment",
            arrayOf("Show Status", "Start From This File", "Stop", "Cancel"),
            0,
            Messages.getQuestionIcon()
        )
        when (choice) {
            0 -> showStatus(project, baseUrl)
            1 -> startFromFile(project, baseUrl, e)
            2 -> stop(project, baseUrl)
            else -> return
        }
    }

    private fun showStatus(project: com.intellij.openapi.project.Project, baseUrl: String) {
        object : Task.Backgroundable(project, "Fetching chaos experiment status", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(MockServerRestClient.buildChaosStatusRequest(baseUrl))
                    when {
                        result.status == 404 -> runOnEdt(project) {
                            MockServerNotifier.notify(project, "No chaos experiment has run since the last reset.", NotificationType.INFORMATION)
                        }
                        result.ok -> runOnEdt(project) {
                            MockServerEditors.openJsonInEditor(project, "chaos-experiment-status.json", MockServerRestClient.prettyPrintJson(result.body))
                        }
                        else -> runOnEdt(project) {
                            MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR)
                        }
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }

    private fun startFromFile(project: com.intellij.openapi.project.Project, baseUrl: String, e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val text = editor?.document?.text
        if (text.isNullOrBlank()) {
            MockServerNotifier.notify(project, "Open a chaos experiment definition file ({ name, stages: [...] }) first.", NotificationType.WARNING)
            return
        }
        if (!MockServerRestClient.isJsonObjectOrArray(text)) {
            MockServerNotifier.notify(project, "The active editor is not a JSON chaos experiment definition.", NotificationType.WARNING)
            return
        }
        object : Task.Backgroundable(project, "Starting chaos experiment", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(MockServerRestClient.buildChaosStartRequest(baseUrl, text))
                    if (result.ok) {
                        runOnEdt(project) {
                            MockServerEditors.openJsonInEditor(project, "chaos-experiment-status.json", MockServerRestClient.prettyPrintJson(result.body))
                            MockServerNotifier.notify(project, "Chaos experiment started.", NotificationType.INFORMATION)
                        }
                    } else {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }

    private fun stop(project: com.intellij.openapi.project.Project, baseUrl: String) {
        object : Task.Backgroundable(project, "Stopping chaos experiment", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(MockServerRestClient.buildChaosStopRequest(baseUrl))
                    // DELETE is idempotent and returns 204 (covered by Result.ok = 2xx).
                    if (result.ok) {
                        runOnEdt(project) { MockServerNotifier.notify(project, "Chaos experiment stopped and chaos cleared.", NotificationType.INFORMATION) }
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
