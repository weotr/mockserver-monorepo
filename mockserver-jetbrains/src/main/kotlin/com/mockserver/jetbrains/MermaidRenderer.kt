package com.mockserver.jetbrains

/**
 * Pure builder for the self-contained HTML document that renders a Mermaid diagram
 * in JCEF (the bundled Chromium). JetBrains has no native Mermaid, so we load
 * mermaid.js (from a CDN) and feed it the diagram source. The diagram text is
 * injected as a JS string literal, NOT as raw HTML, so a malformed/hostile label
 * cannot break out of the `<script>` block.
 *
 * Kept IDE-free (no JCEF import) so the HTML assembly is unit-testable; the live
 * [com.intellij.ui.jcef.JBCefBrowser] wiring lives in [LlmToolWindowFactory].
 */
object MermaidRenderer {

    private const val MERMAID_CDN = "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"

    /**
     * Build a full HTML page that renders [mermaidSource] as a Mermaid diagram,
     * themed for a dark IDE. The diagram source is passed through
     * [escapeForJsString] so embedded quotes/newlines/backslashes are safe inside the
     * JavaScript string literal. A `<noscript>`/error fallback shows the raw source.
     */
    fun toHtml(mermaidSource: String): String {
        val js = escapeForJsString(mermaidSource)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>MockServer agent-run call graph</title>
              <style>
                body { background:#2b2b2b; color:#bbbbbb; font-family:-apple-system, Segoe UI, sans-serif; margin:0; padding:16px; }
                #diagram { text-align:center; }
                pre { color:#dddddd; white-space:pre-wrap; }
                .err { color:#e06c75; }
              </style>
            </head>
            <body>
              <div id="diagram">Rendering…</div>
              <pre id="fallback" style="display:none"></pre>
              <script src="$MERMAID_CDN"></script>
              <script>
                var source = "$js";
                function showFallback(message) {
                  var d = document.getElementById('diagram');
                  var f = document.getElementById('fallback');
                  d.innerHTML = '<span class="err">' + (message || 'Could not load Mermaid renderer.') + '</span>';
                  f.style.display = 'block';
                  f.textContent = source;
                }
                try {
                  if (typeof mermaid === 'undefined') {
                    showFallback('Mermaid library could not be loaded (offline?). Showing the diagram source:');
                  } else {
                    mermaid.initialize({ startOnLoad: false, theme: 'dark' });
                    mermaid.render('graphSvg', source).then(function (result) {
                      document.getElementById('diagram').innerHTML = result.svg;
                    }).catch(function (e) {
                      showFallback('Mermaid could not render this diagram: ' + e);
                    });
                  }
                } catch (e) {
                  showFallback('Mermaid error: ' + e);
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /** Escape [text] for embedding inside a double-quoted JavaScript string literal. */
    fun escapeForJsString(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003C") // avoid closing the <script> element
                '>' -> sb.append("\\u003E")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
