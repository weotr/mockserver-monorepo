package com.mockserver.jetbrains

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Editor completion for `httpLlmResponse` blocks in `*.mockserver.json` files —
 * the JetBrains parity of the VS Code extension's `llmCompletionProvider.ts`.
 *
 * The suggestion logic itself lives in the pure, unit-tested [LlmCompletion]; this
 * contributor is the thin IDE bridge: it only fires inside a `*.mockserver.json`
 * file when the cursor is within an `httpLlmResponse` block (see
 * [LlmCompletion.isInsideLlmResponse]), and adds the curated provider/model/field
 * lookups returned by [LlmCompletion.suggestions]. It does NOT replace the bundled
 * JSON Schema completion — it augments it with a small, opinionated catalogue.
 */
class LlmCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val fileName = parameters.originalFile.name
                    if (!fileName.endsWith(".mockserver.json")) return

                    val document = parameters.editor.document
                    val offset = parameters.offset.coerceIn(0, document.textLength)
                    val textBeforeCursor = document.immutableCharSequence.subSequence(0, offset).toString()
                    if (!LlmCompletion.isInsideLlmResponse(textBeforeCursor)) return

                    for (suggestion in LlmCompletion.suggestions(textBeforeCursor)) {
                        result.addElement(
                            LookupElementBuilder.create(suggestion.insertText)
                                .withPresentableText(suggestion.label)
                                .withTypeText(suggestion.detail, true)
                                .withIcon(com.intellij.icons.AllIcons.Nodes.Plugin)
                        )
                    }
                }
            }
        )
    }
}
