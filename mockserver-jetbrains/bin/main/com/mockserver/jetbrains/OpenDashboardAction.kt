package com.mockserver.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action that opens the MockServer dashboard in the user's default browser at
 * the configured port (see [MockServerSettings]).
 */
class OpenDashboardAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(MockServerSettings.getInstance().dashboardUrl())
    }
}
