package org.mockserver.mock.action.http;

import org.mockserver.model.HttpChaosProfile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of <em>service-scoped</em> HTTP chaos profiles, keyed by
 * upstream host. It lets a single chaos profile be applied to <strong>all</strong>
 * forwarded/proxied requests to a given host without authoring a {@code chaos}
 * block on every forwarding expectation — the ergonomic "break service X" use case.
 *
 * <p>Resolution happens only on the matched-forward path ({@code HttpActionHandler}):
 * when a matched forward expectation carries no {@code chaos} of its own, the
 * registry is consulted with the request's {@code Host} header. A profile attached
 * to the expectation always takes precedence; the anonymous / unmatched proxy
 * fall-through path is intentionally left untouched.
 *
 * <p>Because a service-scoped profile has no single owning expectation, the
 * per-expectation count window ({@code succeedFirst}/{@code failRequestCount}),
 * outage window and degradation ramp (which need a first-match anchor) are not
 * meaningfully gated here — service-scoped profiles are intended for the
 * steady-state faults (error probability, connection drop, latency, body
 * corruption, slow response, and the host-independent quota). The probabilistic,
 * body, slow and quota faults all work as usual.
 *
 * <p>Hosts are matched case-insensitively and ignoring any {@code :port} suffix.
 * State is held in a {@link ConcurrentHashMap} and cleared on server reset
 * (see {@code HttpState.reset()}).
 */
public class ServiceChaosRegistry {

    private static final ServiceChaosRegistry INSTANCE = new ServiceChaosRegistry();

    private final ConcurrentHashMap<String, HttpChaosProfile> byHost = new ConcurrentHashMap<>();

    public static ServiceChaosRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Normalises a host for keying/lookup: lower-cased and without any {@code :port}
     * suffix. Uses the same bracketed-IPv6-aware parsing as {@code Host} header
     * handling ({@link org.mockserver.model.HttpRequest#splitHostPort(String)}), so
     * {@code [::1]:8080} normalises to {@code ::1} rather than being mangled.
     */
    static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = org.mockserver.model.HttpRequest.splitHostPort(trimmed);
        return (parts.length > 0 ? parts[0] : trimmed).toLowerCase();
    }

    /** Register (or replace) the chaos profile for the given host. No-op if either argument is null. */
    public void put(String host, HttpChaosProfile profile) {
        String key = normalizeHost(host);
        if (key == null || key.isEmpty() || profile == null) {
            return;
        }
        byHost.put(key, profile);
    }

    /** Returns the chaos profile registered for the given host, or {@code null} if none. */
    public HttpChaosProfile get(String host) {
        String key = normalizeHost(host);
        return key == null ? null : byHost.get(key);
    }

    /** Removes the chaos profile for the given host (no-op if absent). */
    public void remove(String host) {
        String key = normalizeHost(host);
        if (key != null) {
            byHost.remove(key);
        }
    }

    /** Returns a snapshot copy of the current host → profile mappings. */
    public Map<String, HttpChaosProfile> entries() {
        return new java.util.HashMap<>(byHost);
    }

    /** Clear all service-scoped chaos. Called on server reset and for test isolation. */
    public void reset() {
        byHost.clear();
    }
}
