// Activity Bar "Actions" tree for MockServer: an IntelliJ-tool-window-style
// grouped panel of buttons. The tree has a top status leaf (server + port) whose
// click reveals the docked dashboard, then collapsible groups (Server, Author,
// Inspect, WASM) each holding action leaves. Every leaf's `command` is an
// EXISTING registered command id — this view only adds discoverability, never
// new behaviour.

import * as vscode from "vscode";

// One action leaf: a label, an existing command id to run, an icon, and a tooltip.
interface ActionLeaf {
    label: string;
    command: string;
    icon: string;
    tooltip: string;
}

// One collapsible group: a label, an icon, and its child action leaves.
interface ActionGroup {
    label: string;
    icon: string;
    children: ActionLeaf[];
}

// The static group/leaf structure. The status item is rendered separately (it
// reads the port fresh on each refresh), so it is not part of this list.
const GROUPS: ActionGroup[] = [
    {
        label: "Server",
        icon: "server-process",
        children: [
            {
                label: "Open Dashboard (docked)",
                // Repurposed: now reveals/focuses the docked Panel dashboard view.
                command: "mockserver.openDashboardInEditor",
                icon: "dashboard",
                tooltip: "Reveal the docked MockServer dashboard in the bottom panel",
            },
            {
                label: "Open Dashboard in Browser",
                command: "mockserver.openDashboard",
                icon: "link-external",
                tooltip: "Open the MockServer dashboard in an external browser",
            },
            {
                label: "Start (Docker)",
                command: "mockserver.start",
                icon: "play",
                tooltip: "Start a MockServer Docker container",
            },
            {
                label: "Stop",
                command: "mockserver.stop",
                icon: "debug-stop",
                tooltip: "Stop the MockServer Docker container",
            },
            {
                label: "Reset",
                command: "mockserver.reset",
                icon: "clear-all",
                tooltip: "Clear all expectations and the request log",
            },
        ],
    },
    {
        label: "Author",
        icon: "edit",
        children: [
            {
                label: "Load Into Running Server",
                command: "mockserver.loadExpectations",
                icon: "cloud-upload",
                tooltip: "Load the open expectation file into the running server",
            },
            {
                label: "Diff Against Live",
                command: "mockserver.diffAgainstLive",
                icon: "diff",
                tooltip: "Diff the open expectation file against the live server",
            },
            {
                label: "Save Recorded",
                command: "mockserver.saveRecorded",
                icon: "save",
                tooltip: "Save expectations recorded from proxied traffic (JSON or Java)",
            },
            {
                label: "Generate From OpenAPI",
                command: "mockserver.generateFromOpenApi",
                icon: "file-code",
                tooltip: "Generate expectations from the open OpenAPI/Swagger spec",
            },
        ],
    },
    {
        label: "Inspect",
        icon: "search",
        children: [
            {
                label: "Send Test Request",
                command: "mockserver.sendRequest",
                icon: "run",
                tooltip: "Send the open scratch request to the running server",
            },
            {
                label: "View Request Log",
                command: "mockserver.viewRequestLog",
                icon: "list-unordered",
                tooltip: "Open the server's received-request log",
            },
            {
                label: "Show Drift Report",
                command: "mockserver.showDrift",
                icon: "graph",
                tooltip: "Open the server's drift report",
            },
            {
                label: "Show Drift as Diagnostics",
                command: "mockserver.showDriftDiagnostics",
                icon: "warning",
                tooltip: "Show drift records as inline diagnostics on the open expectation file",
            },
            {
                label: "Find Requests by Trace",
                command: "mockserver.findByTrace",
                icon: "telescope",
                tooltip: "Find every request belonging to a W3C trace id",
            },
            {
                label: "View Trace in Backend",
                command: "mockserver.viewTrace",
                icon: "link-external",
                tooltip: "Open a trace id in your trace backend (Jaeger/Tempo/Grafana)",
            },
        ],
    },
    {
        label: "WASM",
        icon: "circuit-board",
        children: [
            {
                label: "Upload WASM Module",
                command: "mockserver.uploadWasm",
                icon: "cloud-upload",
                tooltip: "Upload a compiled .wasm custom-rule module",
            },
            {
                label: "List WASM Modules",
                command: "mockserver.listWasm",
                icon: "list-flat",
                tooltip: "List the WASM modules registered on the server",
            },
        ],
    },
];

// Discriminated node types backing the tree: the top status leaf, a group, or an
// action leaf.
type Node =
    | { kind: "status" }
    | { kind: "group"; group: ActionGroup }
    | { kind: "leaf"; leaf: ActionLeaf };

/**
 * TreeDataProvider for the `mockserver.actions` view. `getPort` is injected so the
 * status item reflects the configured port read fresh on each refresh (the
 * provider stays decoupled from how the port is sourced).
 */
export class MockServerActionsProvider implements vscode.TreeDataProvider<Node> {
    private readonly changed = new vscode.EventEmitter<Node | undefined | void>();
    readonly onDidChangeTreeData = this.changed.event;

    constructor(private readonly getPort: () => number) {}

    /** Re-emit so the tree re-renders — the status item re-reads the port. */
    refresh(): void {
        this.changed.fire();
    }

    getTreeItem(node: Node): vscode.TreeItem {
        if (node.kind === "status") {
            const item = new vscode.TreeItem(
                `MockServer · localhost:${this.getPort()}`,
                vscode.TreeItemCollapsibleState.None
            );
            item.iconPath = new vscode.ThemeIcon("broadcast");
            item.tooltip = "Open the docked MockServer dashboard";
            // Clicking the status item reveals the docked dashboard panel view.
            item.command = {
                command: "mockserver.openDashboardInEditor",
                title: "Open Dashboard (docked)",
            };
            item.contextValue = "mockserverStatus";
            return item;
        }
        if (node.kind === "group") {
            const item = new vscode.TreeItem(
                node.group.label,
                vscode.TreeItemCollapsibleState.Expanded
            );
            item.iconPath = new vscode.ThemeIcon(node.group.icon);
            item.contextValue = "mockserverGroup";
            return item;
        }
        const item = new vscode.TreeItem(node.leaf.label, vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon(node.leaf.icon);
        item.tooltip = node.leaf.tooltip;
        item.command = { command: node.leaf.command, title: node.leaf.label };
        item.contextValue = "mockserverAction";
        return item;
    }

    getChildren(node?: Node): Node[] {
        if (!node) {
            // Root: the status leaf, then every group.
            const roots: Node[] = [{ kind: "status" }];
            for (const group of GROUPS) {
                roots.push({ kind: "group", group });
            }
            return roots;
        }
        if (node.kind === "group") {
            return node.group.children.map((leaf) => ({ kind: "leaf", leaf }) as Node);
        }
        return [];
    }
}

// Exported for tests: the static groups and their leaf command ids, so a test can
// assert every leaf points at a registered command without reaching into the
// provider's private structure.
export const ACTION_GROUPS = GROUPS;
