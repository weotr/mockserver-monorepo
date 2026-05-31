package org.mockserver.mock.drift;

/**
 * Categories of structural drift detected when comparing a forwarded (real)
 * response against a matching stub expectation's configured response.
 */
public enum DriftType {
    STATUS,
    SCHEMA_FIELD_ADDED,
    SCHEMA_FIELD_REMOVED,
    SCHEMA_TYPE_CHANGED,
    HEADER_ADDED,
    HEADER_REMOVED,
    HEADER_CHANGED
}
