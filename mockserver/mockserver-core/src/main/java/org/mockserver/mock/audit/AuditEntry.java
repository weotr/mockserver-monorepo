package org.mockserver.mock.audit;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * A single control-plane audit record: one mutation (or, when read auditing is
 * enabled, one read) of the MockServer control plane, capturing who/what/when/
 * where/outcome. Deliberately stores NO request headers and NO request body —
 * only redacted, structural metadata — so the audit log can never become a sink
 * for secrets.
 * <p>
 * Records are append-only and immutable once constructed (see {@link AuditStore}).
 */
public class AuditEntry extends ObjectWithReflectiveEqualsHashCodeToString {

    private final long epochTimeMs;
    private final String method;
    private final String path;
    private final String operation;
    private final String sourceAddress;
    private final String principal;
    private final String principalSource;
    private final String outcome;
    private final String summary;

    public AuditEntry(long epochTimeMs, String method, String path, String operation, String sourceAddress, String principal, String principalSource, String outcome, String summary) {
        this.epochTimeMs = epochTimeMs;
        this.method = method;
        this.path = path;
        this.operation = operation;
        this.sourceAddress = sourceAddress;
        this.principal = principal;
        this.principalSource = principalSource;
        this.outcome = outcome;
        this.summary = summary;
    }

    public long getEpochTimeMs() {
        return epochTimeMs;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getOperation() {
        return operation;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getPrincipalSource() {
        return principalSource;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getSummary() {
        return summary;
    }
}
