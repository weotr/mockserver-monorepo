package com.mockserver.jetbrains

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Shared balloon-notification helper for the MockServer actions. */
object MockServerNotifier {

    fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MockServer Notifications")
            .createNotification(content, type)
            .notify(project)
    }
}
