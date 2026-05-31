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
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.*;
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

    final CircularPriorityQueue<String, HttpRequestMatcher, SortableExpectationId> httpRequestMatchers;
    final CircularHashMap<String, RequestDefinition> expectationRequestDefinitions;
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Scheduler scheduler;
    private WebSocketClientRegistry webSocketClientRegistry;
    private MatcherBuilder matcherBuilder;
    private Metrics metrics;
    private final ScenarioManager scenarioManager = new ScenarioManager();

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

    public Expectation add(Expectation expectation, Cause cause) {
        Expectation upsertedExpectation = null;
        if (expectation != null) {
            validateRespondBeforeBody(expectation);
            expectationRequestDefinitions.put(expectation.getId(), expectation.getHttpRequest());
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
                    return httpRequestMatcher;
                })
                .orElseGet(() -> addPrioritisedExpectation(expectation, cause))
                .getExpectation();
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
                        } else {
                            addPrioritisedExpectation(expectation, cause);
                            numberOfChanges.getAndIncrement();
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
        return httpRequestMatchers.size();
    }

    public void reset(Cause cause) {
        httpRequestMatchers.stream().forEach(httpRequestMatcher -> removeHttpRequestMatcher(httpRequestMatcher, cause, false, UUIDService.getUUID()));
        expectationRequestDefinitions.clear();
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
                if (!expectation.consumeMatch()) {
                    httpRequestMatcher.setResponseInProgress(false);
                    continue;
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
                if (!expectation.consumeMatch()) {
                    httpRequestMatcher.setResponseInProgress(false);
                    continue;
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

    Expectation postProcess(Expectation expectation) {
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
                } else if (requestMatcher.matches(httpRequestMatcher.getExpectation().getHttpRequest())) {
                    expectations.add(httpRequestMatcher.getExpectation());
                }
            });
            return expectations;
        }
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
                } else if (requestMatcher.matches(httpRequestMatcher.getExpectation().getHttpRequest())) {
                    httpRequestMatchers.add(httpRequestMatcher);
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
}
