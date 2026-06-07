package org.mockserver.mock;

/**
 * High-level operating mode for MockServer, packaging the common
 * record / replay / pass-through workflows into a single switch (set via
 * {@code PUT /mockserver/mode}). Each mode is a convenience over the existing
 * {@code attemptToProxyIfNoMatchingExpectation} configuration plus the event-log
 * recording that MockServer already performs:
 *
 * <ul>
 *   <li>{@link #SIMULATE} — match expectations and return mocks; unmatched requests get a 404.
 *       This is the default behaviour (proxy-on-no-match disabled).</li>
 *   <li>{@link #SPY} — match expectations and return mocks, but forward unmatched requests to the
 *       real upstream (proxy-on-no-match enabled) so they are served live and recorded.</li>
 *   <li>{@link #CAPTURE} — forward and record; with no expectations defined this captures all
 *       traffic. Backed by the same proxy-on-no-match behaviour as {@link #SPY}.</li>
 * </ul>
 *
 * <p>Recorded interactions are available through the existing recording/retrieve endpoints
 * (e.g. retrieving recorded requests as expectations).
 */
public enum MockMode {
    SIMULATE,
    SPY,
    CAPTURE;

    /**
     * Whether, in this mode, a request that matches no expectation should be proxied to its
     * upstream (and thereby recorded) rather than answered with a 404.
     */
    public boolean proxyUnmatchedRequests() {
        return this != SIMULATE;
    }

    /**
     * Derives the reported mode from the live {@code attemptToProxyIfNoMatchingExpectation} flag.
     * SPY and CAPTURE share the same underlying flag, so an enabled flag reports as {@link #SPY}.
     */
    public static MockMode fromProxyFlag(boolean attemptToProxyIfNoMatchingExpectation) {
        return attemptToProxyIfNoMatchingExpectation ? SPY : SIMULATE;
    }

    /**
     * Parses a mode name case-insensitively.
     *
     * @throws IllegalArgumentException if the value is null/blank or not a known mode
     */
    public static MockMode parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("mode is required (one of SIMULATE, SPY, CAPTURE)");
        }
        try {
            return MockMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown mode '" + value + "' (expected one of SIMULATE, SPY, CAPTURE)");
        }
    }
}
