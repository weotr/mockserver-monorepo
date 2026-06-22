package org.mockserver.slo;

/**
 * Which traffic an SLO sample or objective applies to.
 *
 * <ul>
 *   <li>{@link #FORWARD} — upstream round-trips on the forward/proxy path
 *       (the v1 recording funnel).</li>
 *   <li>{@link #INBOUND} — requests served by MockServer to its own clients.
 *       The inbound recording funnel is deferred; the scope is modelled now so
 *       criteria authored against it parse, but produce no samples yet.</li>
 * </ul>
 */
public enum Scope {
    INBOUND,
    FORWARD
}
