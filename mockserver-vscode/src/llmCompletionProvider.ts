// VS Code wiring for the Phase 6 LLM authoring completion. Offers provider/model
// and field suggestions inside an `httpLlmResponse` block of a *.mockserver.json
// file. All suggestion logic is pure (llmCompletion.ts); this is the thin adapter.

import * as vscode from "vscode";
import { isInsideLlmResponse, llmSuggestions } from "./llmCompletion";

export class LlmCompletionProvider implements vscode.CompletionItemProvider {
    provideCompletionItems(
        document: vscode.TextDocument,
        position: vscode.Position
    ): vscode.CompletionItem[] {
        const textBefore = document.getText(new vscode.Range(new vscode.Position(0, 0), position));
        if (!isInsideLlmResponse(textBefore)) {
            return [];
        }
        return llmSuggestions(textBefore).map((s) => {
            const item = new vscode.CompletionItem(s.label, vscode.CompletionItemKind.Value);
            item.insertText = s.insertText;
            item.detail = s.detail;
            return item;
        });
    }
}
