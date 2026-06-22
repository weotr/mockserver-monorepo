package org.mockserver.mock.drift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.SocketAddress;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Fires a fire-and-forget HTTP POST webhook whenever a {@link DriftRecord} of sufficient severity is
 * stored, carrying the record as JSON. Off by default.
 *
 * <p><b>Decoupling:</b> core must not depend on the Netty HTTP client, so the actual request sender is
 * injected via {@link #setSender(Function)} (mirrors {@code LoadScenarioOrchestrator.setSender} /
 * {@code HttpState.setReplayHandler}). The Netty runtime wires it from
 * {@code HttpActionHandler.getHttpClient()}; unit tests pass a deterministic synchronous fake sender.
 *
 * <p><b>Fail-soft:</b> the entire {@link #onDriftStored(DriftRecord)} body is wrapped in a try/catch that
 * swallows (TRACE-logs) every error, and the outbound send is non-blocking (no {@code .get()}) with an
 * {@code exceptionally} handler. A webhook misconfiguration, a slow/unreachable endpoint, or a malformed
 * URL can therefore never throw into the drift-analysis pipeline nor affect the served response.
 *
 * <p>Time is read via a pluggable {@link LongSupplier} clock (defaults to
 * {@link System#currentTimeMillis()}) so de-dup cooldown behaviour can be driven deterministically in
 * tests without wall-clock sleeps.
 */
public class DriftAlertNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(DriftAlertNotifier.class);

    /** Envelope event type carried by every webhook payload. */
    static final String EVENT = "mockserver.drift.alert";

    /** Hard cap on the de-dup map so a high-cardinality drift stream cannot grow it unbounded. */
    private static final int COOLDOWN_MAP_CAP = 1000;

    private static final DriftAlertNotifier INSTANCE = new DriftAlertNotifier(System::currentTimeMillis);

    private volatile LongSupplier clock;

    /** Sender installed by the runtime; null in unit tests until one is supplied. */
    private volatile Function<HttpRequest, CompletableFuture<HttpResponse>> sender;
    private volatile boolean enabled;
    private volatile String webhookUrl;
    private volatile SemanticSeverity threshold = SemanticSeverity.BREAKING;
    private volatile long cooldownMs = 60000;

    /** signature (expectationId|driftType|field) -> epoch ms it last fired. Bounded by COOLDOWN_MAP_CAP. */
    private final ConcurrentHashMap<String, Long> lastFiredAtMs = new ConcurrentHashMap<>();

    DriftAlertNotifier(LongSupplier clock) {
        this.clock = clock;
    }

    public static DriftAlertNotifier getInstance() {
        return INSTANCE;
    }

    /** Test hook: install a deterministic clock so cooldown windows can be driven without sleeps. */
    void setClock(LongSupplier clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    /**
     * Install the request sender that issues an outbound {@link HttpRequest} and returns the response.
     * Called by the Netty runtime, wiring the existing HTTP client so core never depends on it directly
     * (mirrors {@code LoadScenarioOrchestrator.setSender}). This is runtime wiring, not configuration: it
     * is deliberately not cleared by {@link #reset()}.
     */
    public void setSender(Function<HttpRequest, CompletableFuture<HttpResponse>> sender) {
        this.sender = sender;
    }

    /**
     * Apply the drift-alert webhook configuration. Called by the runtime at startup. A blank URL or a
     * disabled flag leaves the notifier inert.
     */
    public void configure(boolean enabled, String webhookUrl, SemanticSeverity threshold, long cooldownMs) {
        this.enabled = enabled;
        this.webhookUrl = webhookUrl;
        this.threshold = threshold != null ? threshold : SemanticSeverity.BREAKING;
        this.cooldownMs = Math.max(0, cooldownMs);
        lastFiredAtMs.clear();
    }

    /**
     * Notify that a drift record was just stored. Returns immediately (a no-op) unless the notifier is
     * enabled, a sender is installed, the URL is non-blank, the record's effective severity meets the
     * threshold, and the de-dup cooldown allows it. Otherwise builds an outbound POST and fires it
     * fire-and-forget.
     *
     * <p>The whole body is wrapped in a try/catch that swallows every error (TRACE-logged) so it can
     * never throw into the {@link DriftAnalyzer#analyse} store loop.
     */
    public void onDriftStored(DriftRecord record) {
        try {
            if (!enabled || sender == null || record == null) {
                return;
            }
            String url = this.webhookUrl;
            if (url == null || url.isBlank()) {
                return;
            }
            SemanticSeverity effective = effectiveSeverity(record);
            if (!meetsThreshold(effective, this.threshold)) {
                return;
            }
            long now = clock.getAsLong();
            if (!cooldownAllows(signatureOf(record), now)) {
                return;
            }

            HttpRequest outbound = buildOutbound(url, record, effective, now);
            if (outbound == null) {
                return;
            }
            Function<HttpRequest, CompletableFuture<HttpResponse>> currentSender = this.sender;
            if (currentSender == null) {
                return;
            }
            CompletableFuture<HttpResponse> future = currentSender.apply(outbound);
            if (future != null) {
                future.exceptionally(t -> {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("drift alert webhook delivery failed for {}: {}", url, t != null ? t.getMessage() : "null");
                    }
                    return null;
                });
            }
        } catch (Throwable t) {
            // Fail-soft: the webhook must NEVER affect drift analysis or the served response.
            if (LOG.isTraceEnabled()) {
                LOG.trace("drift alert webhook suppressed error: {}", t.getMessage());
            }
        }
    }

    /**
     * Reset transient runtime state. Clears the de-dup cooldown map ONLY; the installed sender and the
     * configuration are runtime wiring (like {@code HttpState}'s replay handler) and are deliberately
     * left intact.
     */
    public void reset() {
        lastFiredAtMs.clear();
    }

    // -- Internal --

    /**
     * Effective severity: the record's LLM-assigned {@link SemanticSeverity} when present, otherwise a
     * deterministic structural fallback keyed on {@link DriftType}.
     */
    static SemanticSeverity effectiveSeverity(DriftRecord record) {
        SemanticSeverity semantic = record.getSemanticSeverity();
        if (semantic != null) {
            return semantic;
        }
        DriftType type = record.getDriftType();
        if (type == null) {
            return SemanticSeverity.WARNING;
        }
        switch (type) {
            case STATUS:
            case SCHEMA_FIELD_REMOVED:
            case SCHEMA_TYPE_CHANGED:
                return SemanticSeverity.BREAKING;
            case HEADER_REMOVED:
            case HEADER_CHANGED:
            case PERFORMANCE:
                return SemanticSeverity.WARNING;
            case SCHEMA_FIELD_ADDED:
            case HEADER_ADDED:
                return SemanticSeverity.INFORMATIONAL;
            default:
                return SemanticSeverity.WARNING;
        }
    }

    /** True when {@code candidate} is at least as severe as {@code threshold} (BREAKING is most severe). */
    static boolean meetsThreshold(SemanticSeverity candidate, SemanticSeverity threshold) {
        // Enum ordinal: BREAKING(0) < WARNING(1) < INFORMATIONAL(2). Lower ordinal = more severe.
        return candidate.ordinal() <= threshold.ordinal();
    }

    private static String signatureOf(DriftRecord record) {
        return record.getExpectationId() + "|" + record.getDriftType() + "|" + record.getField();
    }

    /**
     * Cooldown de-dup: fire (and record) at most once per signature per {@code cooldownMs} window. Bounds
     * the map by evicting entries older than the cooldown when it reaches the cap, and by a hard cap so a
     * pathological cardinality cannot grow it without limit.
     */
    private boolean cooldownAllows(String signature, long now) {
        long window = this.cooldownMs;
        Long last = lastFiredAtMs.get(signature);
        if (last != null && window > 0 && now - last < window) {
            return false;
        }
        if (lastFiredAtMs.size() >= COOLDOWN_MAP_CAP && !lastFiredAtMs.containsKey(signature)) {
            evictStale(now, window);
        }
        lastFiredAtMs.put(signature, now);
        return true;
    }

    private void evictStale(long now, long window) {
        Iterator<Map.Entry<String, Long>> it = lastFiredAtMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (window <= 0 || now - entry.getValue() >= window) {
                it.remove();
            }
        }
        // If still at the cap (all entries are within their cooldown), drop arbitrary entries so the map
        // cannot exceed the cap. The displaced signatures simply lose their cooldown memory — acceptable.
        Iterator<String> keys = lastFiredAtMs.keySet().iterator();
        while (lastFiredAtMs.size() >= COOLDOWN_MAP_CAP && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private HttpRequest buildOutbound(String url, DriftRecord record, SemanticSeverity effective, long now) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("drift alert webhook URL malformed '{}': {}", url, e.getMessage());
            }
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("drift alert webhook URL missing scheme/host '{}'", url);
            }
            return null;
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        int port = uri.getPort();
        if (port == -1) {
            port = https ? 443 : 80;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        String pathWithQuery = query != null && !query.isEmpty() ? path + "?" + query : path;

        ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("event", EVENT);
        envelope.put("epochTimeMs", now);
        envelope.put("severity", effective.name());
        envelope.set("drift", mapper.valueToTree(record));
        String body;
        try {
            body = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("drift alert webhook payload serialization failed: {}", e.getMessage());
            }
            return null;
        }

        int hostPort = port;
        return HttpRequest.request()
            .withMethod("POST")
            .withSocketAddress(host, hostPort, https ? SocketAddress.Scheme.HTTPS : SocketAddress.Scheme.HTTP)
            .withSecure(https)
            .withPath(pathWithQuery)
            .withHeader("Host", hostPort == (https ? 443 : 80) ? host : (host + ":" + hostPort))
            .withContentType(MediaType.APPLICATION_JSON_UTF_8)
            .withBody(body);
    }
}
