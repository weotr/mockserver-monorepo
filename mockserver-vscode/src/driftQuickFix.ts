// The Phase 4 drift quick-fix: a CodeActionProvider that offers an "update stub
// to match upstream" lightbulb on each drift diagnostic. The fix applies a
// targeted text edit (computed purely in driftFix.ts) swapping the stub's
// declared value for the value the real upstream now returns. When no safe edit
// can be computed, it falls back to a non-destructive "diff against live" action.

import * as vscode from "vscode";
import { DriftRecord } from "./mockServerClient";
import { computeDriftFixEdit } from "./driftFix";

/** The diagnostic `code` we tag drift diagnostics with so the provider can find them. */
export const DRIFT_DIAGNOSTIC_CODE = "mockserver.drift";

/**
 * Provides quick-fixes for drift diagnostics. The drift records for each document
 * are supplied by the extension (set when drift diagnostics are computed) so the
 * provider can map a diagnostic line back to its {@link DriftRecord}.
 */
export class DriftQuickFixProvider implements vscode.CodeActionProvider {
    static readonly providedCodeActionKinds = [vscode.CodeActionKind.QuickFix];

    // uri.toString() -> (line -> record). Populated by the extension on drift refresh.
    private readonly recordsByUri = new Map<string, Map<number, DriftRecord>>();

    /** Record the drift records for a document, keyed by the line each attaches to. */
    setRecords(uri: vscode.Uri, lineToRecord: Map<number, DriftRecord>): void {
        this.recordsByUri.set(uri.toString(), lineToRecord);
    }

    /** Forget a document's drift records (e.g. when drift cleared). */
    clearRecords(uri: vscode.Uri): void {
        this.recordsByUri.delete(uri.toString());
    }

    provideCodeActions(
        document: vscode.TextDocument,
        range: vscode.Range | vscode.Selection,
        context: vscode.CodeActionContext
    ): vscode.CodeAction[] {
        const lineToRecord = this.recordsByUri.get(document.uri.toString());
        if (!lineToRecord) {
            return [];
        }
        const actions: vscode.CodeAction[] = [];
        for (const diagnostic of context.diagnostics) {
            if (diagnostic.code !== DRIFT_DIAGNOSTIC_CODE) {
                continue;
            }
            const record = lineToRecord.get(diagnostic.range.start.line);
            if (!record) {
                continue;
            }
            const fix = this.buildFix(document, record, diagnostic);
            if (fix) {
                actions.push(fix);
            }
            actions.push(this.buildDiffFallback(document, diagnostic));
        }
        return actions;
    }

    private buildFix(
        document: vscode.TextDocument,
        record: DriftRecord,
        diagnostic: vscode.Diagnostic
    ): vscode.CodeAction | undefined {
        const edit = computeDriftFixEdit(record, document.getText());
        if (!edit) {
            return undefined;
        }
        const action = new vscode.CodeAction(
            `MockServer: ${edit.description}`,
            vscode.CodeActionKind.QuickFix
        );
        const workspaceEdit = new vscode.WorkspaceEdit();
        workspaceEdit.replace(
            document.uri,
            new vscode.Range(document.positionAt(edit.start), document.positionAt(edit.end)),
            edit.replacement
        );
        action.edit = workspaceEdit;
        action.diagnostics = [diagnostic];
        action.isPreferred = true;
        return action;
    }

    private buildDiffFallback(
        document: vscode.TextDocument,
        diagnostic: vscode.Diagnostic
    ): vscode.CodeAction {
        const action = new vscode.CodeAction(
            "MockServer: Diff stub against live server",
            vscode.CodeActionKind.QuickFix
        );
        action.command = {
            command: "mockserver.diffAgainstLive",
            title: "Diff against live",
            arguments: [document.uri],
        };
        action.diagnostics = [diagnostic];
        return action;
    }
}
