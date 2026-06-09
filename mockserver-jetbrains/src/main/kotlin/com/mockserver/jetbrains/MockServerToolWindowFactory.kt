package com.mockserver.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.*
import java.awt.BorderLayout

/**
 * Tool window factory that creates the MockServer panel.
 * This is an initial stub — future iterations will add status display,
 * expectation management, and log viewing.
 */
class MockServerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = createPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "MockServer", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createPanel(project: Project): JPanel {
        val panel = JPanel(BorderLayout())

        val statusLabel = JLabel("MockServer plugin loaded. Use Tools > MockServer to get started.")
        statusLabel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        panel.add(statusLabel, BorderLayout.NORTH)

        val buttonPanel = JPanel()
        val openDashboardButton = JButton("Open Dashboard")
        openDashboardButton.addActionListener {
            com.intellij.ide.BrowserUtil.browse(OpenDashboardAction.DEFAULT_DASHBOARD_URL)
        }
        buttonPanel.add(openDashboardButton)

        val startDockerButton = JButton("Start MockServer (Docker)")
        startDockerButton.addActionListener {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(
                "docker", "run", "-d", "--rm",
                "--name", StartDockerAction.CONTAINER_NAME,
                "-p", "${StartDockerAction.DEFAULT_PORT}:${StartDockerAction.DEFAULT_PORT}",
                "${StartDockerAction.IMAGE}:${StartDockerAction.MOCKSERVER_VERSION}"
            )
            try {
                com.intellij.execution.process.ProcessHandlerFactory.getInstance()
                    .createProcessHandler(commandLine)
                    .startNotify()
            } catch (ex: Exception) {
                JOptionPane.showMessageDialog(
                    panel,
                    "Failed to start MockServer: ${ex.message}",
                    "MockServer Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
        buttonPanel.add(startDockerButton)

        panel.add(buttonPanel, BorderLayout.CENTER)
        return panel
    }
}
