package org.mockserver.mock.drift;

/**
 * Severity classification assigned by LLM-powered semantic drift analysis.
 * A structural drift that removes a required field is typically BREAKING,
 * while adding an optional field is usually INFORMATIONAL.
 */
public enum SemanticSeverity {
    BREAKING,
    WARNING,
    INFORMATIONAL
}
