// The Phase 5 in-IDE HTTP debugger panel: a webview that shows paused exchanges
// (REQUEST/RESPONSE) and paused stream frames (Phase 7) received over the
// breakpoint callback WebSocket, and lets the user Continue / Modify / Abort
// (Abort = REQUEST phase only) or — per frame — Continue/Modify/Drop/Inject/Close.
//
// PREREQUISITE (surfaced in the UI): breakpoints fire only on traffic flowing
// THROUGH MockServer (proxied/forwarded exchanges, matched mock responses, and
// the unmatched-404 path). Point your app at MockServer as a proxy/mock first.

import * as vscode from "vscode";
import {
    BufferedDecision,
    PausedExchange,
    PausedStreamFrame,
    StreamFrameAction,
    StreamFrameDecisionInput,
    abortAllowed,
    buildDecisionReply,
    buildStreamFrameReply,
} from "./breakpointProtocol";
import { BreakpointClient, BreakpointConnectionState } from "./breakpointClient";

/** The five valid stream-frame actions (StreamFrameDecisionDTO.action). */
const VALID_FRAME_ACTIONS: StreamFrameAction[] = ["CONTINUE", "MODIFY", "DROP", "INJECT", "CLOSE"];

/** A paused item tracked in the panel, keyed by correlationId. */
type PausedItem =
    | { kind: "exchange"; exchange: PausedExchange }
    | { kind: "frame"; frame: PausedStreamFrame };

/**
 * Manages the debugger webview, the live {@link BreakpointClient}, and the set of
 * currently-paused items. One panel per editor session; reveal re-uses it.
 */
export class BreakpointDebuggerPanel {
    public static readonly viewType = "mockserver.breakpointDebugger";
    private panel: vscode.WebviewPanel | undefined;
    private client: BreakpointClient | undefined;
    private state: BreakpointConnectionState = "closed";
    private clientId: string | undefined;
    private readonly paused = new Map<string, PausedItem>();

    constructor(
        private readonly extensionUri: vscode.Uri,
        private readonly makeClient: (handlers: {
            onClientId: (id: string) => void;
            onExchange: (e: PausedExchange) => void;
            onStreamFrame: (f: PausedStreamFrame) => void;
            onState: (s: BreakpointConnectionState) => void;
        }) => BreakpointClient
    ) {}

    /** The clientId the connected callback WS was assigned (for matcher registration). */
    getClientId(): string | undefined {
        return this.clientId;
    }

    /** Whether the callback WS is currently connected. */
    isConnected(): boolean {
        return this.state === "connected";
    }

    /** Reveal (creating if needed) the debugger panel and connect the callback WS. */
    reveal(): void {
        if (this.panel) {
            this.panel.reveal(vscode.ViewColumn.Active);
        } else {
            this.panel = vscode.window.createWebviewPanel(
                BreakpointDebuggerPanel.viewType,
                "MockServer Debugger",
                vscode.ViewColumn.Active,
                { enableScripts: true, retainContextWhenHidden: true }
            );
            this.panel.onDidDispose(() => this.dispose());
            this.panel.webview.onDidReceiveMessage((msg) => this.onWebviewMessage(msg));
        }
        if (!this.client || this.state === "closed" || this.state === "error") {
            this.connect();
        }
        this.render();
    }

    /** Open (or re-open) the callback WS connection. */
    private connect(): void {
        this.client = this.makeClient({
            onClientId: (id) => {
                this.clientId = id;
                this.render();
            },
            onExchange: (exchange) => {
                if (exchange.correlationId) {
                    this.paused.set(exchange.correlationId, { kind: "exchange", exchange });
                    this.render();
                }
            },
            onStreamFrame: (frame) => {
                if (frame.correlationId) {
                    this.paused.set(frame.correlationId, { kind: "frame", frame });
                    this.render();
                }
            },
            onState: (s) => {
                this.state = s;
                this.render();
            },
        });
        this.client.connect();
    }

    /** Handle a decision message from the webview. */
    private onWebviewMessage(msg: unknown): void {
        if (!msg || typeof msg !== "object") {
            return;
        }
        const m = msg as Record<string, unknown>;
        const correlationId = typeof m.correlationId === "string" ? m.correlationId : undefined;
        if (m.command === "reconnect") {
            this.connect();
            return;
        }
        if (!correlationId) {
            return;
        }
        const item = this.paused.get(correlationId);
        if (!item || !this.client) {
            return;
        }
        try {
            if (item.kind === "exchange") {
                this.resolveExchange(item.exchange, m);
            } else {
                this.resolveFrame(item.frame, m);
            }
        } catch (e) {
            vscode.window.showErrorMessage(`MockServer debugger: ${(e as Error).message}`);
            return;
        }
        this.paused.delete(correlationId);
        this.render();
    }

    private resolveExchange(exchange: PausedExchange, m: Record<string, unknown>): void {
        const action = String(m.action);
        let decision: BufferedDecision;
        if (action === "MODIFY") {
            const replacement = this.parseEditedJson(m.body, "modified exchange");
            decision = { action: "MODIFY", replacement };
        } else if (action === "ABORT") {
            if (!abortAllowed(exchange.phase)) {
                throw new Error("Abort is only valid in the REQUEST phase.");
            }
            // Default abort response: 503 (mirrors the dashboard) unless the user
            // supplied a body to send.
            const response =
                typeof m.body === "string" && m.body.trim().length > 0
                    ? this.parseEditedJson(m.body, "abort response")
                    : { statusCode: 503, body: "Aborted by MockServer debugger" };
            decision = { action: "ABORT", response };
        } else {
            decision = { action: "CONTINUE" };
        }
        this.sendReply(this.client!.reply(buildDecisionReply(exchange, decision)));
    }

    /** Warn when a reply could not be sent (no live socket) — the server will auto-continue. */
    private sendReply(sent: boolean): void {
        if (!sent) {
            vscode.window.showWarningMessage(
                "MockServer debugger: the callback connection is closed; the decision was not sent. " +
                "The exchange will auto-continue on the server after its breakpoint timeout."
            );
        }
    }

    private resolveFrame(frame: PausedStreamFrame, m: Record<string, unknown>): void {
        const action = String(m.action) as StreamFrameAction;
        if (!VALID_FRAME_ACTIONS.includes(action)) {
            throw new Error(`Unknown stream-frame action: ${action}`);
        }
        const decision: StreamFrameDecisionInput = { action };
        if ((action === "MODIFY" || action === "INJECT") && typeof m.body === "string") {
            // The webview sends Base64 (already encoded) for stream-frame bodies.
            decision.body = m.body;
        }
        // buildStreamFrameReply throws if MODIFY/INJECT lacks a body — surfaced to the user.
        this.sendReply(this.client!.reply(buildStreamFrameReply(frame, decision)));
    }

    private parseEditedJson(body: unknown, what: string): Record<string, unknown> {
        if (typeof body !== "string") {
            throw new Error(`No edited ${what} provided.`);
        }
        let parsed: unknown;
        try {
            parsed = JSON.parse(body);
        } catch (e) {
            throw new Error(`Edited ${what} is not valid JSON: ${(e as Error).message}`);
        }
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
            throw new Error(`Edited ${what} must be a JSON object.`);
        }
        return parsed as Record<string, unknown>;
    }

    /** Push the current state into the webview. */
    private render(): void {
        if (!this.panel) {
            return;
        }
        this.panel.webview.html = renderDebuggerHtml({
            state: this.state,
            clientId: this.clientId,
            items: Array.from(this.paused.values()),
        });
    }

    dispose(): void {
        if (this.client) {
            this.client.close();
            this.client = undefined;
        }
        this.paused.clear();
        this.panel = undefined;
        this.state = "closed";
    }
}

/** Build the debugger webview HTML. Pure string-building so it is easy to reason about. */
export function renderDebuggerHtml(model: {
    state: BreakpointConnectionState;
    clientId: string | undefined;
    items: PausedItem[];
}): string {
    const dot =
        model.state === "connected"
            ? "🟢"
            : model.state === "connecting"
              ? "🟡"
              : "🔴";
    const itemsHtml = model.items.length === 0
        ? `<p class="muted">No paused exchanges. Register a breakpoint matcher, then drive traffic
           <em>through</em> MockServer (proxy or mock endpoint). Breakpoints fire only on traffic
           flowing through MockServer.</p>`
        : model.items.map(renderItem).join("\n");
    const reconnect =
        model.state === "closed" || model.state === "error"
            ? `<button onclick="post({command:'reconnect'})">Reconnect</button>`
            : "";
    return `<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
<style>
  body { font-family: var(--vscode-font-family); padding: 8px; color: var(--vscode-foreground); }
  .muted { opacity: 0.7; }
  .card { border: 1px solid var(--vscode-panel-border); border-radius: 4px; padding: 8px; margin: 8px 0; }
  .phase { font-weight: bold; }
  textarea { width: 100%; min-height: 8em; font-family: var(--vscode-editor-font-family); }
  button { margin: 4px 4px 0 0; }
  h3 { margin: 4px 0; }
</style></head>
<body>
  <p>${dot} Callback WebSocket: <strong>${escapeHtml(model.state)}</strong>
     ${model.clientId ? `· clientId <code>${escapeHtml(model.clientId)}</code>` : ""} ${reconnect}</p>
  ${itemsHtml}
  <script>
    const vscode = acquireVsCodeApi();
    function post(m){ vscode.postMessage(m); }
    function decide(correlationId, action, textareaId){
      const ta = textareaId ? document.getElementById(textareaId) : null;
      post({ correlationId, action, body: ta ? ta.value : undefined });
    }
  </script>
</body></html>`;
}

function renderItem(item: PausedItem): string {
    if (item.kind === "exchange") {
        return renderExchange(item.exchange);
    }
    return renderFrame(item.frame);
}

function renderExchange(exchange: PausedExchange): string {
    const cid = exchange.correlationId ?? "";
    const id = `edit_${sanitiseId(cid)}`;
    const method =
        typeof exchange.httpRequest["method"] === "string" ? exchange.httpRequest["method"] : "";
    const path = typeof exchange.httpRequest["path"] === "string" ? exchange.httpRequest["path"] : "";
    const editable =
        exchange.phase === "REQUEST"
            ? JSON.stringify(exchange.httpRequest, null, 2)
            : JSON.stringify(exchange.httpResponse ?? {}, null, 2);
    const abortBtn = abortAllowed(exchange.phase)
        ? `<button onclick="decide('${escapeJs(cid)}','ABORT','${id}')">Abort</button>`
        : "";
    const editLabel = exchange.phase === "REQUEST" ? "request" : "response";
    return `<div class="card">
    <h3><span class="phase">${escapeHtml(exchange.phase)}</span> ${escapeHtml(method)} ${escapeHtml(path)}</h3>
    <p class="muted">Edit the ${editLabel} below, then Modify — or Continue with the original.</p>
    <textarea id="${id}">${escapeHtml(editable)}</textarea>
    <div>
      <button onclick="decide('${escapeJs(cid)}','CONTINUE')">Continue</button>
      <button onclick="decide('${escapeJs(cid)}','MODIFY','${id}')">Modify</button>
      ${abortBtn}
    </div>
  </div>`;
}

function renderFrame(frame: PausedStreamFrame): string {
    const cid = frame.correlationId;
    const id = `frame_${sanitiseId(cid)}`;
    return `<div class="card">
    <h3><span class="phase">${escapeHtml(frame.phase)}</span> ${escapeHtml(frame.direction)}
        · ${escapeHtml(frame.streamId)} #${frame.sequenceNumber}</h3>
    <p class="muted">Frame body is Base64. Edit (Base64) then Modify/Inject, or Continue/Drop/Close.</p>
    <textarea id="${id}">${escapeHtml(frame.body)}</textarea>
    <div>
      <button onclick="decide('${escapeJs(cid)}','CONTINUE')">Continue</button>
      <button onclick="decide('${escapeJs(cid)}','MODIFY','${id}')">Modify</button>
      <button onclick="decide('${escapeJs(cid)}','INJECT','${id}')">Inject</button>
      <button onclick="decide('${escapeJs(cid)}','DROP')">Drop</button>
      <button onclick="decide('${escapeJs(cid)}','CLOSE')">Close</button>
    </div>
  </div>`;
}

function sanitiseId(value: string): string {
    return value.replace(/[^a-zA-Z0-9_]/g, "_");
}

function escapeHtml(value: string): string {
    return value
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}

function escapeJs(value: string): string {
    return value.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}
