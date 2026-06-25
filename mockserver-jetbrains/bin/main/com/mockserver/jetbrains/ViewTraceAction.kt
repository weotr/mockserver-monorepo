package com.mockserver.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/**
 * Trace-correlation. Prompts for a W3C trace id (32 hex) or a full `traceparent`
 * header value, resolves the embedded trace id, and opens the correlated trace in
 * the user's browser by substituting it into the configured trace-backend URL
 * template (`MockServerSettings.traceUrlTemplate()`, e.g. a Jaeger/Tempo/Grafana
 * `…/trace/{traceId}` URL).
 *
 * Degrades gracefully when no template is configured: the trace id is copied to the
 * clipboard and surfaced in a notification telling the user to set the template under
 * Settings | Tools | MockServer. The complement of [FindRequestsByTraceAction], which
 * goes the other way (trace id → the received requests that carry it).
 *
 * Purely local — no network call — so everything runs on the EDT.
 */
class ViewTraceAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val input = Messages.showInputDialog(
            project,
            "Trace id (32 hex) or full W3C traceparent to open in your trace backend:",
            "View Trace",
            null
        )
        if (input.isNullOrBlank()) {
            return
        }

        val traceId = MockServerRestClient.extractTraceId(input)
        if (traceId == null) {
            MockServerNotifier.notify(
                project,
                "\"$input\" is not a valid W3C trace id (32 hex) or traceparent (version-traceId-parentId-flags).",
                NotificationType.WARNING
            )
            return
        }

        val url = MockServerRestClient.buildTraceUrl(MockServerSettings.getInstance().traceUrlTemplate(), traceId)
        if (url == null) {
            CopyPasteManager.getInstance().setContents(StringSelection(traceId))
            MockServerNotifier.notify(
                project,
                "Trace $traceId copied to the clipboard. Set a trace-backend URL template " +
                    "(e.g. http://localhost:16686/trace/{traceId}) under Settings | Tools | MockServer " +
                    "to open traces in your browser.",
                NotificationType.INFORMATION
            )
            return
        }

        BrowserUtil.browse(url)
    }
}
