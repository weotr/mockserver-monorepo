package org.mockserver.mock.action.http;

import org.mockserver.model.GrpcChaosProfile;
import org.mockserver.time.TimeService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Process-wide registry of gRPC chaos profiles, keyed by gRPC service name.
 * These profiles are applied by the {@code GrpcToHttpRequestHandler} Netty
 * handler to probabilistically return gRPC error statuses (UNAVAILABLE,
 * DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, INTERNAL, etc.) on matched gRPC
 * method calls.
 *
 * <p>The design mirrors {@link TcpChaosRegistry} — a singleton backed by a
 * {@link ConcurrentHashMap}, with optional TTL-based auto-expiry, service name
 * normalisation (lower-cased, trimmed), and lazy eviction on lookup.
 *
 * <p>An empty-string key ({@code ""}) serves as a default profile that applies
 * to all services unless overridden by a service-specific registration,
 * following the same pattern as {@link org.mockserver.grpc.GrpcHealthRegistry}.
 *
 * <p>State is cleared on server reset (see {@code HttpState.reset()}).
 */
public class GrpcChaosRegistry {

    private static final GrpcChaosRegistry INSTANCE = new GrpcChaosRegistry(TimeService::currentTimeMillis);

    private final ConcurrentHashMap<String, Entry> byService = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> matchCounters = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public GrpcChaosRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public static GrpcChaosRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Normalises a gRPC service name for keying/lookup: lower-cased and trimmed.
     * Empty string is allowed (represents the default/all-services profile).
     */
    static String normalizeService(String service) {
        if (service == null) {
            return null;
        }
        return service.trim().toLowerCase();
    }

    /** Register (or replace) the gRPC chaos profile for the given service with no expiry. */
    public void put(String service, GrpcChaosProfile profile) {
        put(service, profile, 0L);
    }

    /**
     * Register (or replace) the gRPC chaos profile for the given service,
     * optionally with a time-to-live after which it auto-expires.
     *
     * @param ttlMillis milliseconds until the profile auto-expires; {@code <= 0} means no expiry
     */
    public void put(String service, GrpcChaosProfile profile, long ttlMillis) {
        String key = normalizeService(service);
        if (key == null || profile == null) {
            return;
        }
        long expiresAtMillis = ttlMillis > 0 ? saturatingExpiry(clock.getAsLong(), ttlMillis) : 0L;
        byService.put(key, new Entry(profile, expiresAtMillis));
    }

    private static long saturatingExpiry(long now, long ttlMillis) {
        long sum = now + ttlMillis;
        return sum < now ? Long.MAX_VALUE : sum;
    }

    /**
     * Returns the gRPC chaos profile for the given service, falling back to the
     * default ("") profile if no service-specific one exists. Returns {@code null}
     * if neither exists (or both have expired). Expired entries are lazily removed.
     */
    public GrpcChaosProfile get(String service) {
        String key = normalizeService(service);
        if (key == null) {
            return null;
        }
        // try service-specific first
        GrpcChaosProfile result = getEntry(key);
        if (result != null) {
            return result;
        }
        // fall back to the default profile (empty string key)
        if (!key.isEmpty()) {
            return getEntry("");
        }
        return null;
    }

    private GrpcChaosProfile getEntry(String key) {
        Entry entry = byService.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            byService.remove(key, entry);
            return null;
        }
        return entry.profile;
    }

    /**
     * Applies JSON Merge Patch semantics to the gRPC chaos profile for the given
     * service. Only non-null fields from {@code partial} are applied to the
     * existing profile; unset fields in the partial are left unchanged.
     */
    public GrpcChaosProfile patch(String service, GrpcChaosProfile partial) {
        String key = normalizeService(service);
        if (key == null || partial == null) {
            return null;
        }
        byService.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(clock.getAsLong())) {
                return new Entry(partial, 0L);
            }
            GrpcChaosProfile merged = merge(existing.profile, partial);
            return new Entry(merged, existing.expiresAtMillis);
        });
        Entry updated = byService.get(key);
        return updated != null && !updated.isExpired(clock.getAsLong()) ? updated.profile : null;
    }

    private static GrpcChaosProfile merge(GrpcChaosProfile base, GrpcChaosProfile patch) {
        return GrpcChaosProfile.grpcChaosProfile()
            .withErrorStatusCode(patch.getErrorStatusCode() != null ? patch.getErrorStatusCode() : base.getErrorStatusCode())
            .withErrorMessage(patch.getErrorMessage() != null ? patch.getErrorMessage() : base.getErrorMessage())
            .withErrorProbability(patch.getErrorProbability() != null ? patch.getErrorProbability() : base.getErrorProbability())
            .withSeed(patch.getSeed() != null ? patch.getSeed() : base.getSeed())
            .withLatencyMs(patch.getLatencyMs() != null ? patch.getLatencyMs() : base.getLatencyMs())
            .withSucceedFirst(patch.getSucceedFirst() != null ? patch.getSucceedFirst() : base.getSucceedFirst())
            .withFailRequestCount(patch.getFailRequestCount() != null ? patch.getFailRequestCount() : base.getFailRequestCount())
            .withQuotaName(patch.getQuotaName() != null ? patch.getQuotaName() : base.getQuotaName())
            .withQuotaLimit(patch.getQuotaLimit() != null ? patch.getQuotaLimit() : base.getQuotaLimit())
            .withQuotaWindowMillis(patch.getQuotaWindowMillis() != null ? patch.getQuotaWindowMillis() : base.getQuotaWindowMillis());
    }

    /** Removes the gRPC chaos profile for the given service (no-op if absent). */
    public void remove(String service) {
        String key = normalizeService(service);
        if (key != null) {
            byService.remove(key);
            matchCounters.remove(key);
        }
    }

    /** Returns a snapshot copy of the current, non-expired service to profile mappings. */
    public Map<String, GrpcChaosProfile> entries() {
        long now = clock.getAsLong();
        Map<String, GrpcChaosProfile> result = new HashMap<>();
        byService.forEach((key, entry) -> {
            if (!entry.isExpired(now)) {
                result.put(key, entry.profile);
            }
        });
        return result;
    }

    /**
     * Returns, for each currently-active registration that carries a TTL, the
     * remaining milliseconds until it auto-reverts.
     */
    public Map<String, Long> ttlRemainingMillis() {
        long now = clock.getAsLong();
        Map<String, Long> result = new HashMap<>();
        byService.forEach((key, entry) -> {
            if (entry.expiresAtMillis > 0 && !entry.isExpired(now)) {
                result.put(key, entry.expiresAtMillis - now);
            }
        });
        return result;
    }

    /**
     * The gRPC chaos fault types reported by {@link #activeCountByFaultType()}.
     */
    public static final List<String> FAULT_TYPES =
        List.of("error", "latency", "quota");

    /**
     * For each fault type, the number of currently-active (non-expired)
     * registrations whose profile includes that fault.
     */
    public Map<String, Integer> activeCountByFaultType() {
        long now = clock.getAsLong();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String faultType : FAULT_TYPES) {
            counts.put(faultType, 0);
        }
        for (Entry entry : byService.values()) {
            if (entry.isExpired(now)) {
                continue;
            }
            GrpcChaosProfile profile = entry.profile;
            if (profile.getErrorProbability() != null && profile.getErrorProbability() > 0.0) {
                counts.merge("error", 1, Integer::sum);
            }
            if (profile.getLatencyMs() != null && profile.getLatencyMs() > 0) {
                counts.merge("latency", 1, Integer::sum);
            }
            if (profile.getQuotaName() != null && profile.getQuotaLimit() != null && profile.getQuotaWindowMillis() != null) {
                counts.merge("quota", 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Returns the number of non-expired entries. */
    public int activeCount() {
        long now = clock.getAsLong();
        int count = 0;
        for (Entry entry : byService.values()) {
            if (!entry.isExpired(now)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Atomically increments and returns the per-service match counter. Used by
     * the fault decision logic to track how many times a given service has been
     * matched for the count-window check.
     */
    public int incrementMatchCount(String service) {
        String key = normalizeService(service);
        if (key == null) {
            key = "";
        }
        return matchCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** Clear all gRPC chaos profiles and match counters. Called on server reset. */
    public void reset() {
        byService.clear();
        matchCounters.clear();
    }

    private static final class Entry {
        private final GrpcChaosProfile profile;
        private final long expiresAtMillis; // 0 = never expires

        private Entry(GrpcChaosProfile profile, long expiresAtMillis) {
            this.profile = profile;
            this.expiresAtMillis = expiresAtMillis;
        }

        private boolean isExpired(long nowMillis) {
            return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
        }
    }
}
