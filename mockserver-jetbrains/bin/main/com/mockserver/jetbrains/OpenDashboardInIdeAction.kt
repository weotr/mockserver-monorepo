package com.mockserver.jetbrains

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action that shows the live MockServer dashboard inside the IDE by activating the
 * "MockServer Dashboard" tool window (see [MockServerDashboardToolWindowFactory]).
 *
 * Complements [OpenDashboardAction], which opens the dashboard in the external browser.
 */
class OpenDashboardInIdeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project)
            .getToolWindow("MockServerDashboard")
            ?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
