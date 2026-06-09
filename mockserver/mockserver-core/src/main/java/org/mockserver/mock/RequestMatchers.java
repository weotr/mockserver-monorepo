package org.mockserver.mock;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.collections.CircularHashMap;
import org.mockserver.collections.CircularPriorityQueue;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.llm.IsolationSource;
import org.mockserver.llm.LlmScenarioNames;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.LlmConversationMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.*;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.ExpectationEntry;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.StateBackend;
import org.mockserver.state.Versioned;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.*;
import static org.mockserver.metrics.Metrics.Name.*;
import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;
import static org.mockserver.mock.SortableExpectationId.NULL;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class RequestMatchers extends MockServerMatcherNotifier {

    // Node-local cache of compiled HttpRequestMatchers, kept in sync with the
    // backend KeyValueStore<ExpectationEntry>. Tests access this field directly
    // (package-private) so it must remain a functioning CPQ with identical
    // ordering semantics.
    final CircularPriorityQueue<String, HttpRequestMatcher, SortableExpectationId> httpRequestMatchers;
    final CircularHashMap<String, RequestDefinition> expectationRequestDefinitions;
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Scheduler scheduler;
    private WebSocketClientRegistry webSocketClientRegistry;
    private MatcherBuilder matcherBuilder;
    private Metrics metrics;
    private final ScenarioManager scenarioManager = new ScenarioManager();
    // G10 phase 2b: the backend's expectation KV store is the SOURCE OF TRUTH
    // for expectation definitions, ordering, and eviction. The node-local
    // httpRequestMatchers CPQ is a derived cache of compiled matchers.
    private volatile StateBackend stateBackend;
    private volatile KeyValueStore<ExpectationEntry> expectationBackend;
    // Fast id-to-matcher lookup for the node-local cache. Kept in sync with
    // httpRequestMatchers; used to avoid O(n) scans during reconciliation.
    private final ConcurrentHashMap<String, HttpRequestMatcher> matcherCacheById = new ConcurrentHashMap<>();
    // Tracks the backend version that was last reconciled for each expectation
    // id. Used by reconcileFromBackend() to detect remote updates that changed
    // only non-sort fields (e.g. response body) — without this, such updates
    // would leave a stale matcher serving the old behaviour.
    private final ConcurrentHashMap<String, Long> lastReconciledVersion = new ConcurrentHashMap<>();

    public RequestMatchers(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, WebSocketClientRegistry webSocketClientRegistry) {
        super(scheduler);
        this.configuration = configuration;
        this.scheduler = scheduler;
        this.matcherBuilder = new MatcherBuilder(configuration, mockServerLogger);
        this.mockServerLogger = mockServerLogger;
        this.webSocketClientRegistry = webSocketClientRegistry;
        this.metrics = new Metrics(configuration);
        httpRequestMatchers = new CircularPriorityQueue<>(
            configuration.maxExpectations(),
            EXPECTATION_SORTABLE_PRIORITY_COMPARATOR,
            httpRequestMatcher -> httpRequestMatcher.getExpectation() != null ? httpRequestMatcher.getExpectation().getSortableId() : NULL,
            httpRequestMatcher -> httpRequestMatcher.getExpectation() != null ? httpRequestMatcher.getExpectation().getId() : ""
        );
        expectationRequestDefinitions = new CircularHashMap<>(configuration.maxExpectations());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("expectation circular priority queue created, with size " + configuration.maxExpectations())
            );
        }
    }

    /**
     * Sets the state backend reference and wires the expectation KV store
     * as the source of truth. Called by {@code HttpState} after construction.
     * The node-local httpRequestMatchers CPQ becomes a derived cache; all
     * mutations route through the backend first.
     * <p>
     * When a backend is wired, the node-local CPQ's maxSize is raised to
     * {@code Integer.MAX_VALUE} so that eviction is controlled exclusively
     * by the backend (avoiding insertion-order divergence between two CPQs
     * on update-in-place vs re-insert). {@link #reconcileEvictions()} trims
     * the node-local cache after each backend mutation.
     * <p>
     * <b>Threading contract:</b> all control-plane mutations (add, update,
     * remove, clear, reset, setStateBackend) are assumed to be externally
     * serialized — i.e. a single writer at a time. This is satisfied today
     * because {@code HttpState} serializes control-plane calls on the Netty
     * event loop or holds the action lock.
     * <p>
     * TODO(jamesdbloom): phase 2c — when remote invalidation events arrive
     * concurrently from a clustered backend, this single-writer assumption
     * must be revisited — likely by introducing an internal lock or
     * event-queue around the node-local cache reconciliation.
     */
    public void setStateBackend(StateBackend stateBackend) {
        this.stateBackend = stateBackend;
        this.expectationBackend = stateBackend != null ? stateBackend.expectations() : null;
        if (this.expectationBackend != null) {
            // Disable node-local eviction — backend is the eviction authority
            httpRequestMatchers.setMaxSize(Integer.MAX_VALUE);
        } else {
            // Restore original eviction when backend is removed
            httpRequestMatchers.setMaxSize(configuration.maxExpectations());
        }
        // Wire scenario states through the backend's replicated KV store
        // so scenario transitions are shared across cluster nodes. For the
        // default InMemoryStateBackend this wraps a ConcurrentHashMap —
        // identical single-node behaviour with no overhead.
        if (stateBackend != null) {
            scenarioManager.setScenarioStates(stateBackend.scenarioStates());
        } else {
            // Backend removed — reset to a fresh in-memory store so the
            // ScenarioManager doesn't keep operating on a removed/closed
            // backend's store.
            scenarioManager.setScenarioStates(new org.mockserver.state.InMemoryKeyValueStore<>());
        }
    }

    /**
     * Returns the state backend, or {@code null} if none has been set.
     */
    public StateBackend getStateBackend() {
        return stateBackend;
    }

    public Expectation add(Expectation expectation, Cause cause) {
        Expectation upsertedExpectation = null;
        if (expectation != null) {
            validateRespondBeforeBody(expectation);
            expectationRequestDefinitions.put(expectation.getId(), expectation.getHttpRequest());

            // CPX-04: Propagate created time from existing backend entry (if
            // updating) to preserve ordering — must happen before backend put.
            // This is intentionally redundant with the node-local created
            // propagation below (line ~155): the backend propagation covers
            // the case where the node-local CPQ does not yet have the matcher
            // (e.g. after a cold-start rebuild from backend state), while the
            // node-local propagation covers the no-backend fallback path.
            if (expectationBackend != null) {
                expectationBackend.get(expectation.getId()).ifPresent(existing -> {
                    expectation.withCreated(existing.getValue().getExpectation().getCreated());
                });
            }

            upsertedExpectation = httpRequestMatchers
                .getByKey(expectation.getId())
                .map(httpRequestMatcher -> {
                    if (httpRequestMatcher.getExpectation() != null && httpRequestMatcher.getExpectation().getAction() != null) {
                        metrics.decrement(httpRequestMatcher.getExpectation().getAction().getType());
                    }
                    if (httpRequestMatcher.getExpectation() != null) {
                        // propagate created time from previous entry to avoid re-ordering on update
                        expectation.withCreated(httpRequestMatcher.getExpectation().getCreated());
                    }
                    httpRequestMatchers.removePriorityKey(httpRequestMatcher);
                    if (httpRequestMatcher.update(expectation)) {
                        httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(UPDATED_EXPECTATION)
                                    .setLogLevel(Level.INFO)
                                    .setHttpRequest(expectation.getHttpRequest())
                                    .setMessageFormat(UPDATED_EXPECTATION_MESSAGE_FORMAT)
                                    .setArguments(expectation.clone(), expectation.getId())
                            );
                        }
                        if (expectation.getAction() != null) {
                            metrics.increment(expectation.getAction().getType());
                        }
                    } else {
                        httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                    }
                    matcherCacheById.put(expectation.getId(), httpRequestMatcher);
                    return httpRequestMatcher;
                })
                .orElseGet(() -> addPrioritisedExpectation(expectation, cause))
                .getExpectation();

            // Put into backend KV (source of truth) — this may trigger
            // maxExpectations eviction inside the backend's CPQ.
            if (expectationBackend != null) {
                long newVersion = expectationBackend.put(expectation.getId(), new ExpectationEntry(expectation));
                lastReconciledVersion.put(expectation.getId(), newVersion);
                reconcileEvictions();
            }

            notifyListeners(this, cause);
        }
        return upsertedExpectation;
    }

    public void update(Expectation[] expectations, Cause cause) {
        AtomicInteger numberOfChanges = new AtomicInteger(0);
        if (expectations != null) {
            Map<String, HttpRequestMatcher> httpRequestMatchersByKey = httpRequestMatchers.keyMap();
            Set<String> existingKeysForCause = httpRequestMatchersByKey
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().getSource().equals(cause))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            Set<String> addedIds = new HashSet<>();
            Arrays
                .stream(expectations)
                .forEach(expectation -> {
                    // ensure duplicate ids are skipped in input array
                    if (!addedIds.contains(expectation.getId())) {
                        validateRespondBeforeBody(expectation);
                        addedIds.add(expectation.getId());
                        expectationRequestDefinitions.put(expectation.getId(), expectation.getHttpRequest());
                        existingKeysForCause.remove(expectation.getId());

                        // Propagate created time from backend if present
                        if (expectationBackend != null) {
                            expectationBackend.get(expectation.getId()).ifPresent(existing -> {
                                expectation.withCreated(existing.getValue().getExpectation().getCreated());
                            });
                        }

                        if (httpRequestMatchersByKey.containsKey(expectation.getId())) {
                            HttpRequestMatcher httpRequestMatcher = httpRequestMatchersByKey.get(expectation.getId());
                            // update source to new cause
                            httpRequestMatcher.withSource(cause);
                            if (httpRequestMatcher.getExpectation() != null && httpRequestMatcher.getExpectation().getAction() != null) {
                                metrics.decrement(httpRequestMatcher.getExpectation().getAction().getType());
                            }
                            if (httpRequestMatcher.getExpectation() != null) {
                                // propagate created time from previous entry to avoid re-ordering on update
                                expectation.withCreated(httpRequestMatcher.getExpectation().getCreated());
                            }
                            httpRequestMatchers.removePriorityKey(httpRequestMatcher);
                            if (httpRequestMatcher.update(expectation)) {
                                httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                                numberOfChanges.getAndIncrement();
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setType(UPDATED_EXPECTATION)
                                            .setLogLevel(Level.INFO)
                                            .setHttpRequest(expectation.getHttpRequest())
                                            .setMessageFormat(UPDATED_EXPECTATION_MESSAGE_FORMAT)
                                            .setArguments(expectation.clone(), expectation.getId())
                                    );
                                }
                                if (expectation.getAction() != null) {
                                    metrics.increment(expectation.getAction().getType());
                                }
                            } else {
                                httpRequestMatchers.addPriorityKey(httpRequestMatcher);
                            }
                            matcherCacheById.put(expectation.getId(), httpRequestMatcher);
                        } else {
                            addPrioritisedExpectation(expectation, cause);
                            numberOfChanges.getAndIncrement();
                        }

                        // Put into backend KV (source of truth)
                        if (expectationBackend != null) {
                            long newVersion = expectationBackend.put(expectation.getId(), new ExpectationEntry(expectation));
                            lastReconciledVersion.put(expectation.getId(), newVersion);
                        }
                    }
                });
            existingKeysForCause
                .forEach(key -> {
                    numberOfChanges.getAndIncrement();
                    HttpRequestMatcher httpRequestMatcher = httpRequestMatchersByKey.get(key);
                    removeHttpRequestMatcher(httpRequestMatcher, cause, false, UUIDService.getUUID());
                    if (httpRequestMatcher.getExpectation() != null && httpRequestMatcher.getExpectation().getAction() != null) {
                        metrics.decrement(httpRequestMatcher.getExpectation().getAction().getType());
                    }
                });

            // Reconcile evictions after batch update
            if (expectationBackend != null) {
                reconcileEvictions();
            }

            if (numberOfChanges.get() > 0) {
                notifyListeners(this, cause);
            }
        }
    }

    private void validateRespondBeforeBody(Expectation expectation) {
        if (!(expectation.getHttpRequest() instanceof HttpRequest)) {
            return;
        }
        HttpRequest request = (HttpRequest) expectation.getHttpRequest();
        if (!Boolean.TRUE.equals(request.getRespondBeforeBody())) {
            return;
        }
        if (request.getBody() != null) {
            throw new IllegalArgumentException("respondBeforeBody=true cannot be combined with a body matcher: the body has not yet been received when matching occurs");
        }
        if (expectation.getAction() == null) {
            throw new IllegalArgumentException("respondBeforeBody=true requires a RESPONSE or ERROR action");
        }
        Action.Type actionType = expectation.getAction().getType();
        if (actionType != Action.Type.RESPONSE && actionType != Action.Type.ERROR) {
            throw new IllegalArgumentException("respondBeforeBody=true only supports action types RESPONSE and ERROR, was: " + actionType);
        }
    }

    private HttpRequestMatcher addPrioritisedExpectation(Expectation expectation, Cause cause) {
        HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(expectation);
        httpRequestMatchers.add(httpRequestMatcher);
        httpRequestMatcher.withSource(cause);
        matcherCacheById.put(expectation.getId(), httpRequestMatcher);
        if (expectation.getAction() != null) {
            metrics.increment(expectation.getAction().getType());
        }
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(CREATED_EXPECTATION)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(expectation.getHttpRequest())
                    .setMessageFormat(CREATED_EXPECTATION_MESSAGE_FORMAT)
                    .setArguments(expectation.clone(), expectation.getId())
            );
        }
        return httpRequestMatcher;
    }

    public int size() {
        // The node-local cache is kept in sync with the backend, so
        // either source gives the same answer; prefer the CPQ as it
        // is what tests assert against.
        return httpRequestMatchers.size();
    }

    public void reset(Cause cause) {
        httpRequestMatchers.stream().forEach(httpRequestMatcher -> removeHttpRequestMatcher(httpRequestMatcher, cause, false, UUIDService.getUUID()));
        expectationRequestDefinitions.clear();
        matcherCacheById.clear();
        lastReconciledVersion.clear();
        if (expectationBackend != null) {
            expectationBackend.clear();
        }
        scenarioManager.reset();
        Metrics.clearActionMetrics();
        Metrics.clearRequestAndExpectationMetrics();
        notifyListeners(this, cause);
    }

    public void reset() {
        reset(Cause.API);
    }

    public Expectation firstMatchingExpectation(RequestDefinition requestDefinition) {
        Expectation matchedExpectation = null;
        Expectation closestMatchExpectation = null;
        int closestMatchFailures = Integer.MAX_VALUE;
        int totalFields = MatchDifference.Field.values().length;

        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            MatchDifference matchDifference = new MatchDifference(configuration.detailedMatchFailures(), requestDefinition);
            if (httpRequestMatcher.matches(matchDifference, requestDefinition)) {
                Expectation expectation = httpRequestMatcher.getExpectation();

                // Check LLM conversation matcher if present
                HttpLlmResponse llmResponse = expectation.getHttpLlmResponse();
                if (llmResponse != null) {
                    LlmConversationMatcher convMatcher = llmResponse.getConversationMatcher();
                    if (convMatcher != null && convMatcher.hasPredicates()) {
                        if (requestDefinition instanceof HttpRequest) {
                            if (!convMatcher.matches((HttpRequest) requestDefinition)) {
                                continue;
                            }
                        }
                    }
                }

                // Extract isolation key for scenario state management
                String isolationKey = extractIsolationKey(expectation, requestDefinition);

                if (expectation.getScenarioName() != null && expectation.getScenarioState() != null) {
                    if (!scenarioManager.matchesAndTransition(expectation.getScenarioName(), isolationKey, expectation.getScenarioState(), expectation.getNewScenarioState())) {
                        continue;
                    }
                }
                if (!expectation.matchesByPercentage()) {
                    continue;
                }
                httpRequestMatcher.setResponseInProgress(true);
                // Clustered shared-Times CAS: when a clustered backend is active
                // and the expectation has limited Times, atomically decrement the
                // SHARED remaining-times counter via backend CAS BEFORE serving.
                // If the CAS fails (another node exhausted the allotment), this
                // node falls through to the next expectation. Unlimited Times
                // always takes the node-local fast path (no grid call).
                if (isClusteredLimitedTimes(expectation)) {
                    ConsumeTimesResult casResult = consumeTimesViaBackendCas(expectation);
                    if (!casResult.success) {
                        httpRequestMatcher.setResponseInProgress(false);
                        if (casResult.exhausted) {
                            // Expectation is exhausted fleet-wide — schedule
                            // removal so it goes inactive on this node too
                            scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                        }
                        continue;
                    }
                    // CAS succeeded: record the match locally (without
                    // decrementing node-local Times — the backend is authoritative)
                    expectation.consumeMatchLocally();
                } else {
                    // Default single-node fast path: identical to pre-clustering
                    if (!expectation.consumeMatch()) {
                        httpRequestMatcher.setResponseInProgress(false);
                        continue;
                    }
                }
                if (expectation.getScenarioName() != null && expectation.getScenarioState() == null && expectation.getNewScenarioState() != null) {
                    scenarioManager.transitionState(expectation.getScenarioName(), isolationKey, expectation.getNewScenarioState());
                }
                boolean remainingMatchesDecremented = expectation.getTimes() != null && !expectation.getTimes().isUnlimited();
                if (remainingMatchesDecremented) {
                    notifyListeners(this, Cause.API);
                }
                matchedExpectation = expectation;
                break;
            } else {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                }
                int failures = matchDifference.getAllDifferences().size();
                if (failures < closestMatchFailures && httpRequestMatcher.getExpectation() != null) {
                    closestMatchFailures = failures;
                    closestMatchExpectation = httpRequestMatcher.getExpectation();
                }
            }
        }

        if (matchedExpectation == null && closestMatchExpectation != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            int matchedFields = totalFields - closestMatchFailures;
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_NOT_MATCHED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(requestDefinition.getLogCorrelationId())
                    .setHttpRequest(requestDefinition)
                    .setExpectation(closestMatchExpectation)
                    .setMessageFormat("closest expectation:{}matched " + matchedFields + "/" + totalFields + " fields for request:{}")
                    .setArguments(closestMatchExpectation.clone(), requestDefinition)
            );
        }

        if (configuration.metricsEnabled()) {
            if (matchedExpectation == null || matchedExpectation.getAction() == null) {
                metrics.increment(EXPECTATIONS_NOT_MATCHED_COUNT);
            } else if (matchedExpectation.getAction().getType().direction == Action.Direction.FORWARD) {
                metrics.increment(FORWARD_EXPECTATIONS_MATCHED_COUNT);
            } else {
                metrics.increment(RESPONSE_EXPECTATIONS_MATCHED_COUNT);
            }
        }
        return matchedExpectation;
    }

    public Expectation firstMatchingEarlyExpectation(HttpRequest headersOnlyRequest) {
        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            Expectation expectation = httpRequestMatcher.getExpectation();
            if (expectation == null || !(expectation.getHttpRequest() instanceof HttpRequest)) {
                continue;
            }
            HttpRequest expectationRequest = (HttpRequest) expectation.getHttpRequest();
            if (!Boolean.TRUE.equals(expectationRequest.getRespondBeforeBody())) {
                continue;
            }
            if (httpRequestMatcher instanceof org.mockserver.matchers.HttpRequestPropertiesMatcher
                && ((org.mockserver.matchers.HttpRequestPropertiesMatcher) httpRequestMatcher).hasBodyMatcher()) {
                continue;
            }
            if (httpRequestMatcher.matches(null, headersOnlyRequest)) {
                String isolationKey = extractIsolationKey(expectation, headersOnlyRequest);
                if (expectation.getScenarioName() != null && expectation.getScenarioState() != null) {
                    if (!scenarioManager.matchesAndTransition(expectation.getScenarioName(), isolationKey, expectation.getScenarioState(), expectation.getNewScenarioState())) {
                        continue;
                    }
                }
                if (!expectation.matchesByPercentage()) {
                    continue;
                }
                httpRequestMatcher.setResponseInProgress(true);
                // Clustered shared-Times CAS (early/respondBeforeBody path)
                if (isClusteredLimitedTimes(expectation)) {
                    ConsumeTimesResult casResult = consumeTimesViaBackendCas(expectation);
                    if (!casResult.success) {
                        httpRequestMatcher.setResponseInProgress(false);
                        if (casResult.exhausted) {
                            scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                        }
                        continue;
                    }
                    expectation.consumeMatchLocally();
                } else {
                    if (!expectation.consumeMatch()) {
                        httpRequestMatcher.setResponseInProgress(false);
                        continue;
                    }
                }
                if (expectation.getScenarioName() != null && expectation.getScenarioState() == null && expectation.getNewScenarioState() != null) {
                    scenarioManager.transitionState(expectation.getScenarioName(), isolationKey, expectation.getNewScenarioState());
                }
                if (expectation.getTimes() != null && !expectation.getTimes().isUnlimited()) {
                    notifyListeners(this, Cause.API);
                }
                return expectation;
            }
        }
        return null;
    }

    public void clear(RequestDefinition requestDefinition) {
        if (requestDefinition != null) {
            HttpRequestMatcher clearHttpRequestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
            getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
                RequestDefinition request = httpRequestMatcher
                    .getExpectation()
                    .getHttpRequest();
                if (isNotBlank(requestDefinition.getLogCorrelationId())) {
                    request = request
                        .shallowClone()
                        .withLogCorrelationId(requestDefinition.getLogCorrelationId());
                }
                if (clearHttpRequestMatcher.matches(request)) {
                    removeHttpRequestMatcher(httpRequestMatcher, requestDefinition.getLogCorrelationId());
                }
            });
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(CLEARED)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(requestDefinition.getLogCorrelationId())
                        .setHttpRequest(requestDefinition)
                        .setMessageFormat("cleared expectations that match:{}")
                        .setArguments(requestDefinition)
                );
            }
        } else {
            reset();
        }
    }

    public void clear(ExpectationId expectationId, String logCorrelationId) {
        if (expectationId != null) {
            httpRequestMatchers
                .getByKey(expectationId.getId())
                .ifPresent(httpRequestMatcher -> removeHttpRequestMatcher(httpRequestMatcher, logCorrelationId));
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(CLEARED)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("cleared expectations that have id:{}")
                        .setArguments(expectationId.getId())
                );
            }
        } else {
            reset();
        }
    }

    /**
     * Reconciles the node-local HttpRequestMatcher cache against the backend
     * KeyValueStore. This handles three cases:
     * <ol>
     *   <li><b>Eviction:</b> cached matchers whose id is no longer in the
     *       backend are removed (mirrors maxExpectations eviction).</li>
     *   <li><b>Remote add:</b> backend entries with no local matcher get a
     *       new compiled HttpRequestMatcher (enables cross-node visibility
     *       under clustering).</li>
     *   <li><b>Remote update:</b> backend entries whose version is newer
     *       than the locally cached version get their matcher rebuilt.</li>
     * </ol>
     * <p>
     * In single-node / no-backend mode this method is a no-op. When the
     * backend is LOCAL (non-clustered), only eviction applies because all
     * mutations originate locally and the CPQ is already in sync.
     * <p>
     * <b>Threading contract:</b> serialized via {@code synchronized} so
     * that concurrent remote invalidation events (from a clustered backend)
     * do not corrupt the node-local CPQ. Local mutations are still
     * single-writer (Netty event loop / action lock); the lock is
     * reentrant-safe for local callers because Java's {@code synchronized}
     * is reentrant.
     * <p>
     * <b>Concurrent matching (data-plane) note:</b> this method applies
     * incremental per-entry mutations (add/update/remove) to the CPQ — the
     * same granularity as normal control-plane add/remove. The CPQ's
     * {@code toSortedList()} provides an eventually-consistent sorted
     * snapshot via {@code ConcurrentSkipListSet + volatile sortedCache +
     * filter(nonNull)}. A matching thread calling {@code toSortedList()}
     * during a reconcile may see a snapshot that lags by one mutation, but
     * will never see a torn/empty view. This matches the pre-existing
     * control-plane / data-plane concurrency contract.
     */
    public synchronized void reconcileFromBackend() {
        if (expectationBackend == null) {
            return;
        }
        // Snapshot backend state
        Map<String, KeyValueStore.Entry<ExpectationEntry>> backendEntries = new HashMap<>();
        expectationBackend.entries().forEach(e -> backendEntries.put(e.getKey(), e));

        Set<String> backendIds = backendEntries.keySet();

        // 1. Remove evicted matchers (id no longer in backend)
        List<String> evictedIds = new ArrayList<>();
        for (String cachedId : matcherCacheById.keySet()) {
            if (!backendIds.contains(cachedId)) {
                evictedIds.add(cachedId);
            }
        }
        for (String evictedId : evictedIds) {
            HttpRequestMatcher evictedMatcher = matcherCacheById.remove(evictedId);
            if (evictedMatcher != null) {
                httpRequestMatchers.remove(evictedMatcher);
            }
            expectationRequestDefinitions.remove(evictedId);
            lastReconciledVersion.remove(evictedId);
        }

        // 2. Add new entries and update stale entries (remote writes)
        for (Map.Entry<String, KeyValueStore.Entry<ExpectationEntry>> entry : backendEntries.entrySet()) {
            String id = entry.getKey();
            long backendVersion = entry.getValue().getVersion();
            ExpectationEntry backendEntry = entry.getValue().getValue();
            Expectation expectation = backendEntry.getExpectation();

            HttpRequestMatcher existing = matcherCacheById.get(id);
            if (existing == null) {
                // New entry from remote node — build matcher locally
                HttpRequestMatcher newMatcher = matcherBuilder.transformsToMatcher(expectation);
                httpRequestMatchers.add(newMatcher);
                newMatcher.withSource(Cause.API);
                matcherCacheById.put(id, newMatcher);
                expectationRequestDefinitions.put(id, expectation.getHttpRequest());
                lastReconciledVersion.put(id, backendVersion);
                if (expectation.getAction() != null) {
                    metrics.increment(expectation.getAction().getType());
                }
            } else if (existing.getExpectation() != null) {
                // Check if backend version is strictly newer than the last
                // version we reconciled for this id. This catches ALL remote
                // updates — not just sort-field changes (id/priority/created)
                // but also response body, request pattern, or action changes.
                Long lastVersion = lastReconciledVersion.get(id);
                if (lastVersion == null || backendVersion > lastVersion) {
                    // Update the matcher preserving runtime state (Times,
                    // responseInProgress). Re-insert priority key if sort
                    // fields changed.
                    httpRequestMatchers.removePriorityKey(existing);
                    existing.update(expectation);
                    httpRequestMatchers.addPriorityKey(existing);
                    matcherCacheById.put(id, existing);
                    expectationRequestDefinitions.put(id, expectation.getHttpRequest());
                    lastReconciledVersion.put(id, backendVersion);
                }
            }
        }
    }

    /**
     * Backward-compatible alias: reconciles evictions only. Called after
     * local mutations where the node-local CPQ is already up-to-date
     * except for backend eviction. Delegates to the full reconcile.
     */
    private void reconcileEvictions() {
        reconcileFromBackend();
    }

    public Expectation postProcess(Expectation expectation) {
        if (expectation != null) {
            getHttpRequestMatchersCopy()
                .filter(httpRequestMatcher -> httpRequestMatcher.getExpectation() == expectation)
                .findFirst()
                .ifPresent(httpRequestMatcher -> {
                    if (!expectation.isActive()) {
                        removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID());
                    }
                    httpRequestMatcher.setResponseInProgress(false);
                });
        }
        return expectation;
    }

    private void removeHttpRequestMatcher(HttpRequestMatcher httpRequestMatcher, String logCorrelationId) {
        removeHttpRequestMatcher(httpRequestMatcher, Cause.API, true, logCorrelationId);
    }

    @SuppressWarnings("rawtypes")
    private void removeHttpRequestMatcher(HttpRequestMatcher httpRequestMatcher, Cause cause, boolean notifyAndUpdateMetrics, String logCorrelationId) {
        if (httpRequestMatchers.remove(httpRequestMatcher)) {
            // Remove from backend KV and node-local cache
            if (httpRequestMatcher.getExpectation() != null) {
                String id = httpRequestMatcher.getExpectation().getId();
                matcherCacheById.remove(id);
                lastReconciledVersion.remove(id);
                if (expectationBackend != null) {
                    expectationBackend.remove(id);
                }
            }
            if (httpRequestMatcher.getExpectation() != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                Expectation expectation = httpRequestMatcher.getExpectation().clone();
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(REMOVED_EXPECTATION)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setHttpRequest(httpRequestMatcher.getExpectation().getHttpRequest())
                        .setMessageFormat(REMOVED_EXPECTATION_MESSAGE_FORMAT)
                        .setArguments(expectation, expectation.getId())
                );
            }
            if (httpRequestMatcher.getExpectation() != null) {
                clearOrphanedScenarioState(httpRequestMatcher.getExpectation());
                final Action action = httpRequestMatcher.getExpectation().getAction();
                if (action instanceof HttpObjectCallback) {
                    webSocketClientRegistry.unregisterClient(((HttpObjectCallback) action).getClientId());
                }
                if (notifyAndUpdateMetrics && action != null) {
                    metrics.decrement(action.getType());
                }
            }
            if (notifyAndUpdateMetrics) {
                notifyListeners(this, cause);
            }
        }
    }

    private void clearOrphanedScenarioState(Expectation removed) {
        String scenarioName = removed.getScenarioName();
        if (isBlank(scenarioName)) {
            return;
        }
        boolean hasRemaining = httpRequestMatchers.stream()
            .anyMatch(m -> m.getExpectation() != null
                && m.getExpectation() != removed
                && scenarioName.equals(m.getExpectation().getScenarioName()));
        if (!hasRemaining) {
            scenarioManager.clear(scenarioName);
        }
    }

    public Stream<RequestDefinition> retrieveRequestDefinitions(List<ExpectationId> expectationIds) {
        return expectationIds
            .stream()
            .map(expectationId -> {
                if (isBlank(expectationId.getId())) {
                    throw new IllegalArgumentException("No expectation id specified found \"" + expectationId.getId() + "\"");
                }
                if (expectationRequestDefinitions.containsKey(expectationId.getId())) {
                    return expectationRequestDefinitions.get(expectationId.getId());
                } else if (expectationBackend != null) {
                    // Fall back to backend KV as source of truth
                    return expectationBackend.get(expectationId.getId())
                        .map(v -> v.getValue().getExpectation().getHttpRequest())
                        .orElseThrow(() -> new IllegalArgumentException("No expectation found with id " + expectationId.getId()));
                } else {
                    throw new IllegalArgumentException("No expectation found with id " + expectationId.getId());
                }
            })
            .filter(Objects::nonNull);
    }

    public List<Expectation> retrieveActiveExpectations(RequestDefinition requestDefinition) {
        if (requestDefinition == null) {
            return httpRequestMatchers.stream()
                .filter(httpRequestMatcher -> {
                    if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                        scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                        return false;
                    }
                    return true;
                })
                .map(HttpRequestMatcher::getExpectation)
                .collect(Collectors.toList());
        } else {
            List<Expectation> expectations = new ArrayList<>();
            HttpRequestMatcher requestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
            getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                } else {
                    RequestDefinition expectationDefinition = httpRequestMatcher.getExpectation().getHttpRequest();
                    if (notFilterableByRequest(requestDefinition, expectationDefinition) || requestMatcher.matches(expectationDefinition)) {
                        expectations.add(httpRequestMatcher.getExpectation());
                    }
                }
            });
            return expectations;
        }
    }

    /**
     * Determines whether an expectation should bypass reverse-match filtering because the
     * supplied filter cannot describe it.
     *
     * <p>The dashboard UI and the {@code PUT /mockserver/retrieve} endpoint filter active
     * expectations using an HTTP-shaped {@link RequestDefinition} — and when no filter is
     * supplied they send an <em>empty</em> {@link HttpRequest} (matches everything) rather
     * than {@code null}. An HTTP/OpenAPI filter has no vocabulary to express non-HTTP
     * protocol expectations such as {@link DnsRequestDefinition} or
     * {@link BinaryRequestDefinition}: reverse-matching it against them always fails (see
     * {@code HttpRequestPropertiesMatcher#matches}). Filtering on that basis would silently
     * hide every DNS and binary mock from "active expectations" listings (e.g. the Mocks
     * page), even with no filter applied. Such expectations therefore bypass the filter and
     * are always listed whenever the filter itself is HTTP/OpenAPI shaped. A filter of the
     * matching protocol (e.g. a {@link DnsRequestDefinition} filter) still narrows normally.
     */
    private boolean notFilterableByRequest(RequestDefinition filter, RequestDefinition expectationDefinition) {
        boolean httpStyleFilter = filter instanceof HttpRequest || filter instanceof OpenAPIDefinition;
        boolean nonHttpExpectation = !(expectationDefinition instanceof HttpRequest)
            && !(expectationDefinition instanceof OpenAPIDefinition);
        return httpStyleFilter && nonHttpExpectation;
    }

    /**
     * Returns every active expectation whose request matcher matches the given concrete
     * incoming request, using <em>forward</em> matching (the same direction used when
     * serving — "does this expectation match this request?"). This differs from
     * {@link #retrieveActiveExpectations(RequestDefinition)}, which treats its argument
     * as a filter and reverse-matches it against each expectation's definition.
     *
     * <p>Used by drift analysis on the proxy-forward path: a forwarded request needs the
     * set of <em>other</em> matching stubs (e.g. a lower-priority response-type baseline)
     * to diff the real upstream response against. Reverse/filter matching cannot be used
     * there because the concrete request carries headers/cookies that bare stub
     * definitions do not, so it would never match.
     */
    /**
     * Side-effect-free probe: returns the first active expectation whose matcher matches the
     * given request, WITHOUT consuming the match. Specifically, this method avoids:
     * <ul>
     *   <li>Times decrement ({@code consumeMatch()})</li>
     *   <li>Scenario state transition</li>
     *   <li>{@code responseInProgress} flag</li>
     *   <li>Metrics increment</li>
     * </ul>
     * <p>
     * <strong>Note on logging:</strong> the underlying {@code HttpRequestMatcher.matches()} call
     * may still emit {@code INFO}-level {@code EXPECTATION_MATCHED} or {@code EXPECTATION_NOT_MATCHED}
     * log entries as a side-effect of the match evaluation. This method does not suppress those
     * match-diagnostic logs. It is the Times/scenario/responseInProgress/metrics side-effects
     * that are avoided.
     * <p>
     * Used by the gRPC bidi router to decide the routing path before committing to a handler.
     * Callers that need to actually consume the match (decrement Times, transition scenarios,
     * emit logs) must still call {@link #firstMatchingExpectation(RequestDefinition)} separately
     * on the committed path.
     */
    public Expectation peekFirstMatchingExpectation(RequestDefinition requestDefinition) {
        if (requestDefinition == null) {
            return null;
        }
        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            if ((httpRequestMatcher.isResponseInProgress() || httpRequestMatcher.isActive())
                && httpRequestMatcher.matches(requestDefinition)) {
                return httpRequestMatcher.getExpectation();
            }
        }
        return null;
    }

    public List<Expectation> retrieveExpectationsMatchingRequest(RequestDefinition requestDefinition) {
        List<Expectation> expectations = new ArrayList<>();
        if (requestDefinition == null) {
            return expectations;
        }
        getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
            if ((httpRequestMatcher.isResponseInProgress() || httpRequestMatcher.isActive())
                && httpRequestMatcher.matches(requestDefinition)) {
                expectations.add(httpRequestMatcher.getExpectation());
            }
        });
        return expectations;
    }

    public List<HttpRequestMatcher> retrieveRequestMatchers(RequestDefinition requestDefinition) {
        if (requestDefinition == null) {
            return httpRequestMatchers.stream()
                .filter(httpRequestMatcher -> {
                    if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                        scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        } else {
            List<HttpRequestMatcher> httpRequestMatchers = new ArrayList<>();
            HttpRequestMatcher requestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
            getHttpRequestMatchersCopy().forEach(httpRequestMatcher -> {
                if (!httpRequestMatcher.isResponseInProgress() && !httpRequestMatcher.isActive()) {
                    scheduler.submit(() -> removeHttpRequestMatcher(httpRequestMatcher, UUIDService.getUUID()));
                } else {
                    RequestDefinition expectationDefinition = httpRequestMatcher.getExpectation().getHttpRequest();
                    if (notFilterableByRequest(requestDefinition, expectationDefinition) || requestMatcher.matches(expectationDefinition)) {
                        httpRequestMatchers.add(httpRequestMatcher);
                    }
                }
            });
            return httpRequestMatchers;
        }
    }

    public Map<MatchDifference.Field, List<String>> findClosestMatchDiff(HttpRequest httpRequest) {
        int closestMatchFailures = Integer.MAX_VALUE;
        Map<MatchDifference.Field, List<String>> closestDifferences = null;

        for (HttpRequestMatcher httpRequestMatcher : httpRequestMatchers.toSortedList()) {
            MatchDifference matchDifference = new MatchDifference(true, httpRequest);
            if (!httpRequestMatcher.matches(matchDifference, httpRequest)) {
                Map<MatchDifference.Field, List<String>> differences = matchDifference.getAllDifferences();
                int failures = differences.size();
                if (failures < closestMatchFailures && httpRequestMatcher.getExpectation() != null) {
                    closestMatchFailures = failures;
                    closestDifferences = differences;
                }
            }
        }
        return closestDifferences;
    }

    public boolean isEmpty() {
        return httpRequestMatchers.isEmpty();
    }

    public ScenarioManager getScenarioManager() {
        return scenarioManager;
    }

    protected void notifyListeners(final RequestMatchers notifier, Cause cause) {
        super.notifyListeners(notifier, cause);
    }

    private Stream<HttpRequestMatcher> getHttpRequestMatchersCopy() {
        return httpRequestMatchers.stream();
    }

    /**
     * Extract the isolation key from the request based on the expectation's scenario name.
     * Returns null if no isolation is configured (legacy single-key behaviour).
     */
    private String extractIsolationKey(Expectation expectation, RequestDefinition requestDefinition) {
        String scenarioName = expectation.getScenarioName();
        if (scenarioName == null) {
            return null;
        }
        IsolationSource isoSource = LlmScenarioNames.decodeIsolationSource(scenarioName);
        if (isoSource == null) {
            return null;
        }
        if (!(requestDefinition instanceof HttpRequest)) {
            return null;
        }
        HttpRequest request = (HttpRequest) requestDefinition;
        String value = "";
        switch (isoSource.getKind()) {
            case HEADER:
                value = request.getFirstHeader(isoSource.getName());
                break;
            case QUERY_PARAMETER:
                value = request.getFirstQueryStringParameter(isoSource.getName());
                break;
            case COOKIE:
                if (request.getCookies() != null) {
                    for (Cookie cookie : request.getCookieList()) {
                        if (isoSource.getName().equals(cookie.getName().getValue())) {
                            value = cookie.getValue().getValue();
                            break;
                        }
                    }
                }
                break;
        }
        // When the configured attribute is absent, fall back to shared key (null)
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value;
    }

    // --- Clustered shared-Times CAS helpers ---

    /**
     * Returns {@code true} when the expectation has LIMITED Times AND a
     * clustered backend is active. Only in this case does the match
     * consume path need to go through the shared backend CAS.
     * <p>
     * Unlimited Times, no-backend (in-memory default), or non-clustered
     * backends all return {@code false}, keeping the fast path identical
     * to the pre-clustering single-node behaviour.
     */
    private boolean isClusteredLimitedTimes(Expectation expectation) {
        if (stateBackend == null || !stateBackend.isClustered()) {
            return false;
        }
        return expectation.getTimes() != null && !expectation.getTimes().isUnlimited();
    }

    /**
     * Result of a shared-Times CAS attempt on the backend.
     */
    static final class ConsumeTimesResult {
        /** CAS succeeded: this node may serve the response. */
        final boolean success;
        /** The shared counter has reached zero: the expectation is exhausted fleet-wide. */
        final boolean exhausted;

        ConsumeTimesResult(boolean success, boolean exhausted) {
            this.success = success;
            this.exhausted = exhausted;
        }
    }

    /**
     * Atomically decrements the shared remaining-times counter in the
     * backend via compare-and-set (CAS). This is the correctness-critical
     * distributed path that ensures a {@code Times.exactly(N)} expectation
     * is served exactly N times across the whole fleet.
     * <p>
     * <b>Algorithm:</b>
     * <ol>
     *   <li>Read the current {@link ExpectationEntry} and its version
     *       from the backend.</li>
     *   <li>If the entry is absent or its {@code remainingTimes} is
     *       already {@code <= 0}, return failure + exhausted.</li>
     *   <li>Build a new entry with {@code remainingTimes - 1} and attempt
     *       {@link KeyValueStore#compareAndSet} with the read version.</li>
     *   <li>If CAS fails (concurrent write from another node), retry
     *       from step 1 (bounded to {@code MAX_CAS_RETRIES}).</li>
     *   <li>If CAS succeeds, return success.</li>
     * </ol>
     * <p>
     * <b>Bounded retries:</b> if the CAS loop exhausts all retries
     * without succeeding (extreme contention), the method returns failure.
     * This is a conservative choice: the expectation is not served rather
     * than risking a double-serve. In practice, CAS contention is rare
     * because limited-Times expectations are low-count by nature.
     *
     * @param expectation the expectation whose shared Times to consume
     * @return the CAS result indicating success or failure/exhaustion
     */
    ConsumeTimesResult consumeTimesViaBackendCas(Expectation expectation) {
        final int MAX_CAS_RETRIES = 10;
        final String id = expectation.getId();

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            Optional<Versioned<ExpectationEntry>> current = expectationBackend.get(id);
            if (!current.isPresent()) {
                // Entry gone (removed by another node or evicted)
                return new ConsumeTimesResult(false, true);
            }
            Versioned<ExpectationEntry> versioned = current.get();
            ExpectationEntry entry = versioned.getValue();
            long version = versioned.getVersion();

            if (entry.getRemainingTimes() <= 0) {
                // Already exhausted across the fleet
                return new ConsumeTimesResult(false, true);
            }

            // Build a decremented copy
            int newRemaining = entry.getRemainingTimes() - 1;
            ExpectationEntry decremented = new ExpectationEntry(entry, newRemaining);

            if (expectationBackend.compareAndSet(id, version, decremented)) {
                // CAS succeeded — this node wins this match slot
                return new ConsumeTimesResult(true, false);
            }
            // CAS failed — another node wrote concurrently; retry with fresh read
        }

        // Exhausted retries without success — conservative failure
        return new ConsumeTimesResult(false, false);
    }
}
