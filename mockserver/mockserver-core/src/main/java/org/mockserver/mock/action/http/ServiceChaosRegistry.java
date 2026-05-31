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

    /**
     * Applies JSON Merge Patch semantics to the chaos profile for the given host.
     * Only non-null fields from {@code partial} are applied to the existing profile;
     * unset fields in the partial are left unchanged. If no profile exists for the
     * host, the partial IS registered as a new profile (with no TTL). No-op if
     * either argument is null.
     *
     * @return the updated profile, or null if host/partial is null
     */
    public HttpChaosProfile patch(String host, HttpChaosProfile partial) {
        String key = normalizeHost(host);
        if (key == null || key.isEmpty() || partial == null) {
            return null;
        }
        byHost.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(clock.getAsLong())) {
                // no existing profile — treat partial as a new registration (no TTL)
                return new Entry(partial, 0L);
            }
            HttpChaosProfile merged = merge(existing.profile, partial);
            return new Entry(merged, existing.expiresAtMillis);
        });
        Entry updated = byHost.get(key);
        return updated != null && !updated.isExpired(clock.getAsLong()) ? updated.profile : null;
    }

    private static HttpChaosProfile merge(HttpChaosProfile base, HttpChaosProfile patch) {
        return HttpChaosProfile.httpChaosProfile()
            .withErrorStatus(patch.getErrorStatus() != null ? patch.getErrorStatus() : base.getErrorStatus())
            .withRetryAfter(patch.getRetryAfter() != null ? patch.getRetryAfter() : base.getRetryAfter())
            .withErrorProbability(patch.getErrorProbability() != null ? patch.getErrorProbability() : base.getErrorProbability())
            .withDropConnectionProbability(patch.getDropConnectionProbability() != null ? patch.getDropConnectionProbability() : base.getDropConnectionProbability())
            .withLatency(patch.getLatency() != null ? patch.getLatency() : base.getLatency())
            .withSeed(patch.getSeed() != null ? patch.getSeed() : base.getSeed())
            .withSucceedFirst(patch.getSucceedFirst() != null ? patch.getSucceedFirst() : base.getSucceedFirst())
            .withFailRequestCount(patch.getFailRequestCount() != null ? patch.getFailRequestCount() : base.getFailRequestCount())
            .withOutageAfterMillis(patch.getOutageAfterMillis() != null ? patch.getOutageAfterMillis() : base.getOutageAfterMillis())
            .withOutageDurationMillis(patch.getOutageDurationMillis() != null ? patch.getOutageDurationMillis() : base.getOutageDurationMillis())
            .withTruncateBodyAtFraction(patch.getTruncateBodyAtFraction() != null ? patch.getTruncateBodyAtFraction() : base.getTruncateBodyAtFraction())
            .withMalformedBody(patch.getMalformedBody() != null ? patch.getMalformedBody() : base.getMalformedBody())
            .withSlowResponseChunkSize(patch.getSlowResponseChunkSize() != null ? patch.getSlowResponseChunkSize() : base.getSlowResponseChunkSize())
            .withSlowResponseChunkDelay(patch.getSlowResponseChunkDelay() != null ? patch.getSlowResponseChunkDelay() : base.getSlowResponseChunkDelay())
            .withQuotaName(patch.getQuotaName() != null ? patch.getQuotaName() : base.getQuotaName())
            .withQuotaLimit(patch.getQuotaLimit() != null ? patch.getQuotaLimit() : base.getQuotaLimit())
            .withQuotaWindowMillis(patch.getQuotaWindowMillis() != null ? patch.getQuotaWindowMillis() : base.getQuotaWindowMillis())
            .withQuotaErrorStatus(patch.getQuotaErrorStatus() != null ? patch.getQuotaErrorStatus() : base.getQuotaErrorStatus())
            .withDegradationRampMillis(patch.getDegradationRampMillis() != null ? patch.getDegradationRampMillis() : base.getDegradationRampMillis());
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

    /**
     * The HTTP chaos fault types reported by {@link #activeCountByFaultType()},
     * matching the {@code fault_type} label values of the
     * {@code mock_server_http_chaos_injected} counter.
     */
    public static final java.util.List<String> FAULT_TYPES =
        java.util.List.of("drop", "error", "latency", "truncate", "malformed", "slow", "quota");

    /**
     * For each fault type, the number of currently-active (non-expired)
     * service-scoped registrations whose profile includes that fault. A profile
     * carrying several faults (e.g. error + latency) is counted under each, so the
     * per-type counts may sum to more than the number of registered hosts. Every
     * fault type in {@link #FAULT_TYPES} is always present in the returned map (0
     * when none), giving a stable, complete set of series for the
     * {@code mock_server_active_service_chaos} gauge so an operator can see — and
     * alert on — which kinds of chaos are live, dropping to 0 as profiles are
     * cleared or their TTLs lapse.
     *
     * <p>Iterates the {@link ConcurrentHashMap} weakly-consistently, so under
     * concurrent registration/removal the counts may transiently reflect a mix of
     * pre- and post-mutation state — acceptable for a gauge metric.
     */
    public Map<String, Integer> activeCountByFaultType() {
        long now = clock.getAsLong();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String faultType : FAULT_TYPES) {
            counts.put(faultType, 0);
        }
        for (Entry entry : byHost.values()) {
            if (entry.isExpired(now)) {
                continue;
            }
            HttpChaosProfile profile = entry.profile;
            if (profile.getDropConnectionProbability() != null) {
                counts.merge("drop", 1, Integer::sum);
            }
            if (profile.getErrorStatus() != null) {
                counts.merge("error", 1, Integer::sum);
            }
            if (profile.getLatency() != null) {
                counts.merge("latency", 1, Integer::sum);
            }
            if (profile.getTruncateBodyAtFraction() != null) {
                counts.merge("truncate", 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(profile.getMalformedBody())) {
                counts.merge("malformed", 1, Integer::sum);
            }
            // slow and quota only fire with their companion fields present, so an
            // incomplete (no-op) config is not counted — matching HttpActionHandler.
            if (profile.getSlowResponseChunkSize() != null && profile.getSlowResponseChunkDelay() != null) {
                counts.merge("slow", 1, Integer::sum);
            }
            if (profile.getQuotaName() != null && profile.getQuotaLimit() != null && profile.getQuotaWindowMillis() != null) {
                counts.merge("quota", 1, Integer::sum);
            }
        }
        return counts;
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
