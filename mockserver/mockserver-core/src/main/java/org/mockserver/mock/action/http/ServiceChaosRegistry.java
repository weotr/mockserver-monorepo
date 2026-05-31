package org.mockserver.mock.action.http;

import org.mockserver.model.HttpChaosProfile;
import org.mockserver.time.TimeService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
 * <p><b>Time-to-live (auto-revert):</b> a registration may carry an optional
 * {@code ttlMillis}. When set, the profile auto-expires that many milliseconds
 * after registration and is removed lazily on the next lookup — a "dead-man's
 * switch" so chaos started by an external orchestrator self-heals even if the
 * orchestrator never sends the matching clear (e.g. it crashed mid-experiment).
 * Expiry is measured with the controllable clock ({@link TimeService}), so it
 * tracks real wall-clock time by default but is deterministic under clock
 * freeze/advance for tests.
 *
 * <p>Hosts are matched case-insensitively and ignoring any {@code :port} suffix.
 * State is held in a {@link ConcurrentHashMap} and cleared on server reset
 * (see {@code HttpState.reset()}).
 */
public class ServiceChaosRegistry {

    private static final ServiceChaosRegistry INSTANCE = new ServiceChaosRegistry(TimeService::currentTimeMillis);

    private final ConcurrentHashMap<String, Entry> byHost = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ServiceChaosRegistry(LongSupplier clock) {
        this.clock = clock;
    }

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

    /** Register (or replace) the chaos profile for the given host with no expiry. No-op if either argument is null. */
    public void put(String host, HttpChaosProfile profile) {
        put(host, profile, 0L);
    }

    /**
     * Register (or replace) the chaos profile for the given host, optionally with a
     * time-to-live after which it auto-expires.
     *
     * @param ttlMillis milliseconds until the profile auto-expires; {@code <= 0} means no expiry
     */
    public void put(String host, HttpChaosProfile profile, long ttlMillis) {
        String key = normalizeHost(host);
        if (key == null || key.isEmpty() || profile == null) {
            return;
        }
        long expiresAtMillis = ttlMillis > 0 ? saturatingExpiry(clock.getAsLong(), ttlMillis) : 0L;
        byHost.put(key, new Entry(profile, expiresAtMillis));
    }

    /**
     * {@code now + ttlMillis}, saturating at {@link Long#MAX_VALUE} instead of
     * overflowing to a negative value (which would be misread as "never expires"
     * by {@link Entry#isExpired}). Both arguments are positive here.
     */
    private static long saturatingExpiry(long now, long ttlMillis) {
        long sum = now + ttlMillis;
        return sum < now ? Long.MAX_VALUE : sum;
    }

    /**
     * Returns the chaos profile registered for the given host, or {@code null} if
     * none (or it has expired — an expired entry is removed lazily here).
     */
    public HttpChaosProfile get(String host) {
        String key = normalizeHost(host);
        if (key == null) {
            return null;
        }
        Entry entry = byHost.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            // remove only if it is still the same entry, so a concurrent re-registration is not lost
            byHost.remove(key, entry);
            return null;
        }
        return entry.profile;
    }

    /** Removes the chaos profile for the given host (no-op if absent). */
    public void remove(String host) {
        String key = normalizeHost(host);
        if (key != null) {
            byHost.remove(key);
        }
    }

    /** Returns a snapshot copy of the current, non-expired host → profile mappings. */
    public Map<String, HttpChaosProfile> entries() {
        long now = clock.getAsLong();
        Map<String, HttpChaosProfile> result = new HashMap<>();
        byHost.forEach((key, entry) -> {
            if (!entry.isExpired(now)) {
                result.put(key, entry.profile);
            }
        });
        return result;
    }

    /**
     * Returns, for each currently-active registration that carries a TTL, the
     * remaining milliseconds until it auto-reverts. Entries with no TTL (or that
     * have already expired) are omitted. Used to surface a remaining-TTL countdown
     * on {@code GET /mockserver/serviceChaos}.
     */
    public Map<String, Long> ttlRemainingMillis() {
        long now = clock.getAsLong();
        Map<String, Long> result = new HashMap<>();
        byHost.forEach((key, entry) -> {
            if (entry.expiresAtMillis > 0 && !entry.isExpired(now)) {
                result.put(key, entry.expiresAtMillis - now);
            }
        });
        return result;
    }

    /** Clear all service-scoped chaos. Called on server reset and for test isolation. */
    public void reset() {
        byHost.clear();
    }

    private static final class Entry {
        private final HttpChaosProfile profile;
        private final long expiresAtMillis; // 0 = never expires

        private Entry(HttpChaosProfile profile, long expiresAtMillis) {
            this.profile = profile;
            this.expiresAtMillis = expiresAtMillis;
        }

        private boolean isExpired(long nowMillis) {
            return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
        }
    }
}
