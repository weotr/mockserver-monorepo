// Pure, `vscode`-free logic for the Phase 4 drift quick-fix ("update stub to
// match upstream"). Given a drift record and the expectation file text, compute
// a concrete text edit that updates the stub's declared value to the value the
// real upstream now returns — when the drifted value can be located in the JSON.
//
// This is intentionally a TEXT-level edit driven by the server-reported
// `expectedValue`, not a JSON re-serialisation: it preserves the file's existing
// formatting and only touches the one literal that drifted. When the expected
// value cannot be located unambiguously, no edit is produced and the caller falls
// back to a non-destructive action (e.g. open the live diff).

import { DriftRecord } from "./mockServerClient";

/** A computed text replacement: replace `[start, end)` (character offsets) with `replacement`. */
export interface DriftTextEdit {
    /** Inclusive start character offset into the document text. */
    start: number;
    /** Exclusive end character offset into the document text. */
    end: number;
    /** The replacement text (already JSON-encoded as it should appear in the file). */
    replacement: string;
    /** A human description of what the edit does, for the quick-fix title. */
    description: string;
}

/** JSON-encode a primitive/object value the way it should appear in the file. */
function encodeValue(value: unknown): string {
    return JSON.stringify(value);
}

/**
 * Find the character offset range of the JSON literal for `expectedValue` within
 * the expectation identified by `expectationId`. Heuristic and conservative:
 *
 * 1. locate the expectation's object span (from its `"id": "<id>"` line to the
 *    next top-level expectation or end), then
 * 2. find the FIRST occurrence of the JSON-encoded `expectedValue` within that
 *    span.
 *
 * Returns `null` when the id or the value cannot be located — the caller then
 * declines to offer a destructive edit. Pure and `vscode`-free.
 */
function locateExpectedValue(
    docText: string,
    expectationId: string,
    expectedValue: unknown
): { start: number; end: number } | null {
    if (!expectationId) {
        return null;
    }
    const encodedId = encodeValue(expectationId); // e.g. "exp-1"
    const idMarker = `"id"`;
    // Find a line/region that contains both the "id" key and the encoded id value.
    const idKeyIndex = findIdAnchor(docText, idMarker, encodedId);
    if (idKeyIndex < 0) {
        return null;
    }
    // Span: from the id anchor to the start of the next `"id"` occurrence (or EOF).
    const nextId = docText.indexOf(idMarker, idKeyIndex + idMarker.length);
    const spanEnd = nextId < 0 ? docText.length : nextId;
    const encodedExpected = encodeValue(expectedValue);
    const valueStart = docText.indexOf(encodedExpected, idKeyIndex);
    if (valueStart < 0 || valueStart >= spanEnd) {
        return null;
    }
    return { start: valueStart, end: valueStart + encodedExpected.length };
}

/** Find the offset of an `"id"` key whose value (on the same line) equals encodedId. */
function findIdAnchor(docText: string, idMarker: string, encodedId: string): number {
    let from = 0;
    for (;;) {
        const idx = docText.indexOf(idMarker, from);
        if (idx < 0) {
            return -1;
        }
        // The encoded id must appear after the key on the same logical line.
        const lineEnd = docText.indexOf("\n", idx);
        const segmentEnd = lineEnd < 0 ? docText.length : lineEnd;
        if (docText.indexOf(encodedId, idx) >= 0 && docText.indexOf(encodedId, idx) < segmentEnd) {
            return idx;
        }
        from = idx + idMarker.length;
    }
}

/**
 * Compute the drift quick-fix text edit for a record against the expectation file
 * text, or `null` when no safe edit can be made (the value can't be located, or
 * the drift type has no concrete `expectedValue`/`actualValue` to swap). The edit
 * replaces the stub's `expectedValue` literal with the upstream `actualValue`.
 */
export function computeDriftFixEdit(record: DriftRecord, docText: string): DriftTextEdit | null {
    // We can only do a value-swap when BOTH the old (expected) and new (actual)
    // values are present — added/removed schema fields have no literal to swap.
    if (record.expectedValue === undefined || record.actualValue === undefined) {
        return null;
    }
    const located = locateExpectedValue(docText, record.expectationId, record.expectedValue);
    if (!located) {
        return null;
    }
    return {
        start: located.start,
        end: located.end,
        replacement: encodeValue(record.actualValue),
        description: `Update ${record.field}: ${encodeValue(record.expectedValue)} → ${encodeValue(
            record.actualValue
        )}`,
    };
}
