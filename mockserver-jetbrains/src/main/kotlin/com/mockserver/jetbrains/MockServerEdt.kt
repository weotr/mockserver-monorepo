package com.mockserver.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * Runs [block] on the IntelliJ Event Dispatch Thread, guarded against the project
 * being disposed before the runnable executes.
 *
 * The MockServer actions do their network I/O on a background [com.intellij.openapi.progress.Task.Backgroundable]
 * thread and post their result (a notification or a new editor tab) back onto the
 * EDT. If the user closes the project — or the tool window — while a request is in
 * flight, the project can be disposed before the `invokeLater` runnable runs,
 * touching disposed services from inside it throws `AlreadyDisposedException`.
 *
 * The expiry-condition overload of `invokeLater` drops the runnable entirely once
 * `project.isDisposed` becomes true, so the result is silently discarded for a
 * project that no longer exists. This single shared helper replaces the nine
 * identical, unguarded `runOnEdt` copies previously inlined in each action.
 */
fun runOnEdt(project: Project, block: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(
        block,
        ModalityState.defaultModalityState()
    ) { project.isDisposed }
}
