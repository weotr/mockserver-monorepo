package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Uploads a compiled WebAssembly (`.wasm`) custom-rule module to the running
 * MockServer via `PUT /mockserver/wasm/modules?name=<name>`. The user picks a
 * `.wasm` file with the IDE file chooser and confirms the module name (defaulting
 * to the file name without extension); the raw bytes are sent as
 * `application/octet-stream`. Once registered the module can be referenced by name
 * as a WASM body matcher in an expectation.
 *
 * The file chooser, name dialog and notifications run on the EDT; the HTTP upload
 * runs on a background thread.
 */
class UploadWasmModuleAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("wasm")
            .withTitle("Select WASM Module")
            .withDescription("Choose a compiled .wasm custom-rule module to upload to MockServer")
        val vFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        val name = Messages.showInputDialog(
            project,
            "Module name (used to reference this module as a WASM body matcher):",
            "Upload WASM Module",
            null,
            vFile.nameWithoutExtension,
            null
        )
        if (name.isNullOrBlank()) {
            return
        }

        val bytes = try {
            vFile.contentsToByteArray()
        } catch (ex: Exception) {
            MockServerNotifier.notify(project, "Failed to read ${vFile.name}: ${ex.message}", NotificationType.ERROR)
            return
        }

        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Uploading WASM module to MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildWasmUploadRequest(baseUrl, name, bytes)
                    )
                    if (result.ok) {
                        runOnEdt(project) {
                            MockServerNotifier.notify(
                                project,
                                "Uploaded WASM module \"$name\". Reference it in an expectation body matcher as { \"type\": \"WASM\", \"moduleName\": \"$name\" }.",
                                NotificationType.INFORMATION
                            )
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
}
