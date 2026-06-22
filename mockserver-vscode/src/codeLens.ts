import * as vscode from "vscode";
import { detectMockServerUsage, isCodeAwareFile } from "./codeAware";

/**
 * Adds "Load into running MockServer", "Verify", "Diff against live", and "Delete"
 * actions at the top of every `*.mockserver.json(c)` file, turning a static
 * expectation file into a live control surface.
 */
export class ExpectationCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const topOfFile = new vscode.Range(0, 0, 0, 0);
        return [
            new vscode.CodeLens(topOfFile, {
                title: "$(cloud-upload) Load into running server",
                command: "mockserver.loadExpectations",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(verified) Verify",
                command: "mockserver.verifyExpectations",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(diff) Diff against live",
                command: "mockserver.diffAgainstLive",
                arguments: [document.uri],
            }),
            new vscode.CodeLens(topOfFile, {
                title: "$(trash) Delete",
                command: "mockserver.deleteExpectations",
                arguments: [document.uri],
            }),
        ];
    }
}

/** Document selector matching the expectation files this extension understands. */
export const EXPECTATION_FILE_SELECTOR: vscode.DocumentSelector = [
    { scheme: "file", pattern: "**/*.mockserver.json" },
    { scheme: "file", pattern: "**/*.mockserver.jsonc" },
];

/**
 * Adds a "Send to MockServer" action at the top of every
 * `*.mockserver-request.json` scratch-request file, so a developer can fire the
 * request at the running mock and see the response in one click.
 */
export class ScratchRequestCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const topOfFile = new vscode.Range(0, 0, 0, 0);
        return [
            new vscode.CodeLens(topOfFile, {
                title: "$(run) Send to MockServer",
                command: "mockserver.sendRequest",
                arguments: [document.uri],
            }),
        ];
    }
}

/** Document selector matching the scratch-request files this extension understands. */
export const REQUEST_FILE_SELECTOR: vscode.DocumentSelector = [
    { scheme: "file", pattern: "**/*.mockserver-request.json" },
];

/**
 * Phase 4 test/code-aware integration: adds a run/inspect CodeLens above each
 * detected MockServer usage site (`new MockServerClient(...)`,
 * `@MockServerSettings`, Testcontainers `MockServerContainer`) in source files.
 * Detection is a best-effort regex scan (see codeAware.ts), not a full AST parse.
 */
export class CodeAwareCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        if (!isCodeAwareFile(document.uri.fsPath)) {
            return [];
        }
        return detectMockServerUsage(document.getText()).map((hit) => {
            const range = new vscode.Range(hit.line, 0, hit.line, 0);
            return new vscode.CodeLens(range, {
                title: `$(server) ${hit.label}`,
                command: "mockserver.openDashboardInEditor",
                arguments: [],
            });
        });
    }
}

/** Document selector for source files the code-aware CodeLens scans. */
export const CODE_AWARE_FILE_SELECTOR: vscode.DocumentSelector = [
    { scheme: "file", language: "java" },
    { scheme: "file", language: "kotlin" },
    { scheme: "file", language: "javascript" },
    { scheme: "file", language: "typescript" },
    { scheme: "file", language: "groovy" },
    { scheme: "file", language: "scala" },
];
