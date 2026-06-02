package org.mockserver.model;

/**
 * Controls what happens to an expectation's primary response when a <em>blocking</em>
 * {@link AfterAction before-action} fails or times out.
 *
 * <ul>
 *   <li>{@link #FAIL_FAST} — abort the primary action and return an error response to the
 *   client (the downstream side-effect is treated as a precondition for responding).</li>
 *   <li>{@link #BEST_EFFORT} — log the failure and continue to the primary action anyway
 *   (the side-effect is advisory).</li>
 * </ul>
 *
 * <p>Only meaningful for before-actions; ignored by after-actions, which are always
 * fire-and-forget and run after the response has been written.</p>
 */
public enum FailurePolicy {
    FAIL_FAST,
    BEST_EFFORT
}
