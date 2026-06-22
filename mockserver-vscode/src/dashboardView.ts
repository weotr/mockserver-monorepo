// Docked MockServer dashboard, rendered as a WebviewView in the bottom Panel
// (NOT an editor tab). Mirrors the JetBrains plugin's tool window: the dashboard
// lives in a dock you can show/hide without it taking an editor slot. Reuses the
// same iframe HTML/CSP as the old editor-tab dashboard via the `vscode`-free
// `buildDashboardWebviewHtml` helper in mockServerClient.

import * as vscode from "vscode";
import * as client from "./mockServerClient";

/**
 * WebviewViewProvider for the `mockserver.dashboard` Panel view. Builds the same
 * localhost dashboard URL the external-browser command uses, framed in a webview
 * whose CSP allows `frame-src http://localhost:*`. `getPort` is injected so the
 * view always frames the currently-configured port.
 */
export class MockServerDashboardViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = "mockserver.dashboard";

    // Held so we can re-render the html if the underlying view was disposed and
    // is later revealed again.
    private view: vscode.WebviewView | undefined;

    constructor(private readonly getPort: () => number) {}

    private dashboardUrl(): string {
        return `${client.buildBaseUrl(this.getPort())}/mockserver/dashboard`;
    }

    resolveWebviewView(webviewView: vscode.WebviewView): void {
        this.view = webviewView;
        webviewView.webview.options = { enableScripts: true };
        webviewView.webview.html = client.buildDashboardWebviewHtml(this.dashboardUrl());

        webviewView.onDidDispose(() => {
            if (this.view === webviewView) {
                this.view = undefined;
            }
        });

        // Re-render when the view becomes visible again, so a view disposed while
        // hidden comes back with fresh html (and the current configured port).
        webviewView.onDidChangeVisibility(() => {
            if (webviewView.visible && webviewView.webview.html.length === 0) {
                webviewView.webview.html = client.buildDashboardWebviewHtml(this.dashboardUrl());
            }
        });
    }
}
