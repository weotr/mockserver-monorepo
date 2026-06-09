package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.TcpChaosProfileDTO;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-wide registry of TCP-layer chaos profiles, keyed by host. These
 * profiles are applied by the {@code TcpChaosHandler} Netty handler at the
 * raw byte level before HTTP decoding, injecting transport-layer faults such
 * as latency, connection drops, bandwidth throttling, TCP RST, data slicing,
 * and data limits.
 *
 * <p>The design mirrors {@link ServiceChaosRegistry} — a singleton backed by
 * a {@link ConcurrentHashMap}, with optional TTL-based auto-expiry, host
 * normalisation (case-insensitive, port-stripped), and lazy eviction on
 * lookup.
 *
 * <p>State is cleared on server reset (see {@code HttpState.reset()}).
 *
 * <p><b>Fleet-awareness (G11):</b> when a clustered {@link StateBackend} is
 * wired via {@link #setStateBackend(StateBackend)}, mutations are replicated
 * via the backend's {@code crudEntities("chaos-tcp")} store, and an
 * {@link InvalidationListener} rebuilds the node-local map on remote writes.
 * The {@link #get(String)} path remains purely node-local. When no backend is
 * set or the backend is not clustered, behaviour is identical to the pre-G11
 * node-local-only registry.
 */
public class TcpChaosRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TcpChaosRegistry.class);
    static final String BACKEND_NAMESPACE = "chaos-tcp";

    private static final TcpChaosRegistry INSTANCE = new TcpChaosRegistry(TimeService::currentTimeMillis);

    private final ConcurrentHashMap<String, Entry> byHost = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    // G11: optional clustered backend for fleet replication
    private volatile KeyValueStore<ObjectNode> backendStore;

    public TcpChaosRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public static TcpChaosRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Wires the clustered state backend for fleet-wide chaos replication.
     * When the backend {@link StateBackend#isClustered() isClustered()},
     * mutations are replicated via the backend's CRUD entity store, and
     * an {@link InvalidationListener} is registered to rebuild the
     * node-local map on remote writes. When the backend is not clustered,
     * this method is a no-op — the registry stays purely node-local.
     */
    public void setStateBackend(StateBackend backend) {
        if (backend != null && backend.isClustered()) {
            this.backendStore = backend.crudEntities(BACKEND_NAMESPACE);
        }
    }

    /**
     * Normalises a host for keying/lookup: lower-cased and without any {@code :port}
     * suffix. Uses the same bracketed-IPv6-aware parsing as {@link ServiceChaosRegistry}.
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

    /** Register (or replace) the TCP chaos profile for the given host with no expiry. */
    public void put(String host, TcpChaosProfile profile) {
        put(host, profile, 0L);
    }

    /**
     * Register (or replace) the TCP chaos profile for the given host, optionally with a
     * time-to-live after which it auto-expires.
     *
     * @param ttlMillis milliseconds until the profile auto-expires; {@code <= 0} means no expiry
     */
    public void put(String host, TcpChaosProfile profile, long ttlMillis) {
        String key = normalizeHost(host);
        if (key == null || key.isEmpty() || profile == null) {
            return;
        }
        long expiresAtMillis = ttlMillis > 0 ? saturatingExpiry(clock.getAsLong(), ttlMillis) : 0L;
        byHost.put(key, new Entry(profile, expiresAtMillis));
        writeToBackend(key, profile, expiresAtMillis);
    }

    private static long saturatingExpiry(long now, long ttlMillis) {
        long sum = now + ttlMillis;
        return sum < now ? Long.MAX_VALUE : sum;
    }

    /**
     * Returns the TCP chaos profile registered for the given host, or {@code null} if
     * none (or it has expired — an expired entry is removed lazily here).
     */
    public TcpChaosProfile get(String host) {
        String key = normalizeHost(host);
        if (key == null) {
            return null;
        }
        Entry entry = byHost.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            byHost.remove(key, entry);
            return null;
        }
        return entry.profile;
    }

    /**
     * Applies JSON Merge Patch semantics to the TCP chaos profile for the given host.
     * Only non-null fields from {@code partial} are applied to the existing profile;
     * unset fields in the partial are left unchanged.
     */
    public TcpChaosProfile patch(String host, TcpChaosProfile partial) {
        String key = normalizeHost(host);
        if (key == null || key.isEmpty() || partial == null) {
            return null;
        }
        byHost.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(clock.getAsLong())) {
                return new Entry(partial, 0L);
            }
            TcpChaosProfile merged = merge(existing.profile, partial);
            return new Entry(merged, existing.expiresAtMillis);
        });
        Entry updated = byHost.get(key);
        if (updated != null && !updated.isExpired(clock.getAsLong())) {
            writeToBackend(key, updated.profile, updated.expiresAtMillis);
            return updated.profile;
        }
        return null;
    }

    private static TcpChaosProfile merge(TcpChaosProfile base, TcpChaosProfile patch) {
        return TcpChaosProfile.tcpChaosProfile()
            .withLatencyMs(patch.getLatencyMs() != null ? patch.getLatencyMs() : base.getLatencyMs())
            .withDown(patch.getDown() != null ? patch.getDown() : base.getDown())
            .withBandwidthBytesPerSec(patch.getBandwidthBytesPerSec() != null ? patch.getBandwidthBytesPerSec() : base.getBandwidthBytesPerSec())
            .withSlowClose(patch.getSlowClose() != null ? patch.getSlowClose() : base.getSlowClose())
            .withTimeout(patch.getTimeout() != null ? patch.getTimeout() : base.getTimeout())
            .withResetPeer(patch.getResetPeer() != null ? patch.getResetPeer() : base.getResetPeer())
            .withSlicerChunkSize(patch.getSlicerChunkSize() != null ? patch.getSlicerChunkSize() : base.getSlicerChunkSize())
            .withLimitDataBytes(patch.getLimitDataBytes() != null ? patch.getLimitDataBytes() : base.getLimitDataBytes());
    }

    /** Removes the TCP chaos profile for the given host (no-op if absent). */
    public void remove(String host) {
        String key = normalizeHost(host);
        if (key != null) {
            byHost.remove(key);
            removeFromBackend(key);
        }
    }

    /** Returns a snapshot copy of the current, non-expired host to profile mappings. */
    public Map<String, TcpChaosProfile> entries() {
        long now = clock.getAsLong();
        Map<String, TcpChaosProfile> result = new HashMap<>();
        byHost.forEach((key, entry) -> {
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
        byHost.forEach((key, entry) -> {
            if (entry.expiresAtMillis > 0 && !entry.isExpired(now)) {
                result.put(key, entry.expiresAtMillis - now);
            }
        });
        return result;
    }

    /**
     * The TCP chaos fault types reported by {@link #activeCountByFaultType()}.
     */
    public static final List<String> FAULT_TYPES =
        List.of("latency", "down", "bandwidth", "slow_close", "timeout", "reset_peer", "slicer", "limit_data");

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
        for (Entry entry : byHost.values()) {
            if (entry.isExpired(now)) {
                continue;
            }
            TcpChaosProfile profile = entry.profile;
            if (profile.getLatencyMs() != null && profile.getLatencyMs() > 0) {
                counts.merge("latency", 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(profile.getDown())) {
                counts.merge("down", 1, Integer::sum);
            }
            if (profile.getBandwidthBytesPerSec() != null && profile.getBandwidthBytesPerSec() > 0) {
                counts.merge("bandwidth", 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(profile.getSlowClose())) {
                counts.merge("slow_close", 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(profile.getTimeout())) {
                counts.merge("timeout", 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(profile.getResetPeer())) {
                counts.merge("reset_peer", 1, Integer::sum);
            }
            if (profile.getSlicerChunkSize() != null && profile.getSlicerChunkSize() > 0) {
                counts.merge("slicer", 1, Integer::sum);
            }
            if (profile.getLimitDataBytes() != null && profile.getLimitDataBytes() > 0) {
                counts.merge("limit_data", 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Returns the number of non-expired entries. */
    public int activeCount() {
        long now = clock.getAsLong();
        int count = 0;
        for (Entry entry : byHost.values()) {
            if (!entry.isExpired(now)) {
                count++;
            }
        }
        return count;
    }

    /** Clear all TCP-layer chaos. Called on server reset and for test isolation. */
    public void reset() {
        byHost.clear();
        clearBackend();
    }

    // --- G11: backend write-through and reconciliation ---

    private void writeToBackend(String key, TcpChaosProfile profile, long expiresAtMillis) {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.set("profile", mapper.valueToTree(new TcpChaosProfileDTO(profile)));
            node.put("expiresAtMillis", expiresAtMillis);
            store.put(key, node);
        } catch (Exception e) {
            LOG.warn("failed to write TCP chaos to backend for key={}", key, e);
        }
    }

    private void removeFromBackend(String key) {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            store.remove(key);
        } catch (Exception e) {
            LOG.warn("failed to remove TCP chaos from backend for key={}", key, e);
        }
    }

    private void clearBackend() {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            store.clear();
        } catch (Exception e) {
            LOG.warn("failed to clear TCP chaos backend", e);
        }
    }

    /**
     * Rebuilds the node-local map from the backend store. Called by the
     * {@link InvalidationListener} when a remote write is detected.
     */
    public void reconcileFromBackend() {
        KeyValueStore<ObjectNode> store = this.backendStore;
        if (store == null) {
            return;
        }
        try {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            Map<String, Entry> newEntries = new HashMap<>();
            store.entries().forEach(entry -> {
                try {
                    ObjectNode node = entry.getValue();
                    TcpChaosProfileDTO dto = mapper.treeToValue(node.get("profile"), TcpChaosProfileDTO.class);
                    long expiresAtMillis = node.path("expiresAtMillis").asLong(0L);
                    TcpChaosProfile profile = dto.buildObject();
                    newEntries.put(entry.getKey(), new Entry(profile, expiresAtMillis));
                } catch (Exception e) {
                    LOG.warn("failed to deserialize TCP chaos entry key={}", entry.getKey(), e);
                }
            });
            byHost.keySet().removeIf(k -> !newEntries.containsKey(k));
            byHost.putAll(newEntries);
        } catch (Exception e) {
            LOG.warn("failed to reconcile TCP chaos from backend", e);
        }
    }

    private static final class Entry {
        private final TcpChaosProfile profile;
        private final long expiresAtMillis; // 0 = never expires

        private Entry(TcpChaosProfile profile, long expiresAtMillis) {
            this.profile = profile;
            this.expiresAtMillis = expiresAtMillis;
        }

        private boolean isExpired(long nowMillis) {
            return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
        }
    }
}
