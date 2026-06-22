package com.mockserver.jetbrains

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.util.Function

/**
 * Phase 4 gutter run-affordance: draws a MockServer gutter icon next to lines that use
 * MockServer — `MockServerClient`, `@MockServerSettings`, `@MockServerTest`, or
 * `MockServerContainer` — so a developer can jump from their test/client code straight
 * to the embedded dashboard (or the breakpoint debugger) for that instance.
 *
 * Detection is best-effort textual ([MockServerCodeUsage]) rather than per-language PSI:
 * the plugin targets Community across all JetBrains IDEs/languages, where a universal
 * AST is not available. We only mark **leaf** elements (no children) to attach the icon
 * to a single concrete token rather than every enclosing node — the platform requires
 * line markers on leaf elements anyway.
 */
class MockServerGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only leaf elements, per the platform contract for line markers.
        if (element.firstChild != null) return null
        val text = element.text ?: return null
        // Cheap pre-filter before the regex set.
        if (!text.contains("MockServer")) return null
        val marker = MockServerCodeUsage.detect(text) ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Toolwindows.WebToolWindow,
            Function { "MockServer · ${marker.label} — click to open the MockServer dashboard" },
            { _, elt ->
                val project = elt.project
                ToolWindowManager.getInstance(project).getToolWindow("MockServerDashboard")?.activate(null)
            },
            GutterIconRenderer.Alignment.LEFT,
            { "MockServer ${marker.label}" }
        )
    }
}
