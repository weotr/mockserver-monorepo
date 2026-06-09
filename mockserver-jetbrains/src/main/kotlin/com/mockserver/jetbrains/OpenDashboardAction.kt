package com.mockserver.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action that opens the MockServer dashboard in the user's default browser.
 * Defaults to http://localhost:1080/mockserver/dashboard.
 */
class OpenDashboardAction : AnAction() {

    companion object {
        const val DEFAULT_DASHBOARD_URL = "http://localhost:1080/mockserver/dashboard"
    }

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(DEFAULT_DASHBOARD_URL)
    }
}
