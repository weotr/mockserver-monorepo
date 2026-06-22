package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.CLEARED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for expectation namespacing / multi-tenancy (WS7.4).
 *
 * <p>Uses an instance {@link Configuration} (never static
 * {@code ConfigurationProperties}) so these tests mutate no global state and do
 * not need to be registered in the sequential Surefire phase.</p>
 *
 * @author jamesdbloom
 */
public class RequestMatchersNamespaceTest {

    private static final String NAMESPACE_HEADER = "X-MockServer-Namespace";

    private RequestMatchers requestMatchers;

    @Before
    public void prepareTestFixture() {
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry webSocketClientRegistry = mock(WebSocketClientRegistry.class);
        // default config: matchNamespaceHeader defaults to X-MockServer-Namespace
        requestMatchers = new RequestMatchers(configuration(), new MockServerLogger(), scheduler, webSocketClientRegistry);
    }

    private static HttpRequest requestInNamespace(String namespace) {
        return request().withPath("somePath").withHeader(NAMESPACE_HEADER, namespace);
    }

    @Test
    public void namespacedExpectationMatchesWhenRequestCarriesMatchingNamespaceHeader() {
        // given
        Expectation teamA = new Expectation(request().withPath("somePath"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("team-a-body"));
        requestMatchers.add(teamA, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(requestInNamespace("team-a")), is(teamA));
    }

    @Test
    public void namespacedExpectationDoesNotMatchRequestFromAnotherNamespace() {
        // given
        Expectation teamA = new Expectation(request().withPath("somePath"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("team-a-body"));
        requestMatchers.add(teamA, API);

        // then - a team-b request must NOT see team-a's expectation
        assertThat(requestMatchers.firstMatchingExpectation(requestInNamespace("team-b")), nullValue());
    }

    @Test
    public void namespacedExpectationDoesNotMatchRequestWithNoNamespaceHeader() {
        // given
        Expectation teamA = new Expectation(request().withPath("somePath"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("team-a-body"));
        requestMatchers.add(teamA, API);

        // then - no-header request sees only global expectations (true isolation)
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("somePath")), nullValue());
    }

    @Test
    public void globalExpectationMatchesRegardlessOfNamespaceHeader() {
        // given - no namespace = global
        Expectation global = new Expectation(request().withPath("somePath"))
            .thenRespond(response().withBody("global-body"));
        requestMatchers.add(global, API);

        // then - matches with any namespace header...
        assertThat(requestMatchers.firstMatchingExpectation(requestInNamespace("team-a")), is(global));
        // ...and matches with no namespace header
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("somePath")), is(global));
    }

    @Test
    public void requestMatchesItsOwnNamespaceAndGlobalButNotOtherTenants() {
        // given three expectations on the SAME path in different namespaces
        Expectation teamA = new Expectation(request().withPath("somePath"))
            .withPriority(10)
            .withNamespace("team-a")
            .thenRespond(response().withBody("team-a-body"));
        Expectation teamB = new Expectation(request().withPath("somePath"))
            .withPriority(10)
            .withNamespace("team-b")
            .thenRespond(response().withBody("team-b-body"));
        Expectation global = new Expectation(request().withPath("somePath"))
            .withPriority(0)
            .thenRespond(response().withBody("global-body"));
        requestMatchers.add(teamA, API);
        requestMatchers.add(teamB, API);
        requestMatchers.add(global, API);

        // then - team-a request gets the higher-priority team-a expectation
        assertThat(requestMatchers.firstMatchingExpectation(requestInNamespace("team-a")), is(teamA));
        // and a request in an unknown namespace falls through to global
        assertThat(requestMatchers.firstMatchingExpectation(requestInNamespace("team-unknown")), is(global));
    }

    @Test
    public void namespaceHeaderDoesNotParticipateInNormalHeaderMatching() {
        // given an existing expectation that matches on an unrelated header only
        Expectation expectation = new Expectation(request().withPath("somePath").withHeader("X-Custom", "value"))
            .thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // when a request carries BOTH the declared header and a namespace header
        HttpRequest httpRequest = request()
            .withPath("somePath")
            .withHeader("X-Custom", "value")
            .withHeader(NAMESPACE_HEADER, "team-a");

        // then the extra namespace header does not break matching of a global expectation
        assertThat(requestMatchers.firstMatchingExpectation(httpRequest), is(expectation));
    }

    @Test
    public void clearByNamespaceRemovesOnlyThatNamespacesExpectations() {
        // given
        Expectation teamA = new Expectation(request().withPath("a"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("a"));
        Expectation teamB = new Expectation(request().withPath("b"))
            .withNamespace("team-b")
            .thenRespond(response().withBody("b"));
        Expectation global = new Expectation(request().withPath("g"))
            .thenRespond(response().withBody("g"));
        requestMatchers.add(teamA, API);
        requestMatchers.add(teamB, API);
        requestMatchers.add(global, API);

        // when
        requestMatchers.clearByNamespace("team-a", "correlation-id");

        // then - team-a gone, team-b and global intact
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("a").withHeader(NAMESPACE_HEADER, "team-a")), nullValue());
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("b").withHeader(NAMESPACE_HEADER, "team-b")), is(teamB));
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("g")), is(global));
    }

    @Test
    public void clearByBlankNamespaceIsNoOp() {
        // given
        Expectation teamA = new Expectation(request().withPath("a"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("a"));
        Expectation global = new Expectation(request().withPath("g"))
            .thenRespond(response().withBody("g"));
        requestMatchers.add(teamA, API);
        requestMatchers.add(global, API);

        // when - blank namespace must not clear anything (especially not global)
        requestMatchers.clearByNamespace("", "correlation-id");

        // then - both still present
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("a").withHeader(NAMESPACE_HEADER, "team-a")), is(teamA));
        assertThat(requestMatchers.firstMatchingExpectation(request().withPath("g")), is(global));
    }

    @Test
    public void customNamespaceHeaderNameIsHonoured() {
        // given a matcher configured with a custom namespace header name
        Configuration configuration = configuration().matchNamespaceHeader("X-Tenant");
        RequestMatchers customMatchers = new RequestMatchers(configuration, new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        Expectation teamA = new Expectation(request().withPath("somePath"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("team-a-body"));
        customMatchers.add(teamA, API);

        // then - the default header name no longer scopes the request
        assertThat(customMatchers.firstMatchingExpectation(request().withPath("somePath").withHeader("X-Tenant", "team-a")), is(teamA));
        assertThat(customMatchers.firstMatchingExpectation(request().withPath("somePath").withHeader(NAMESPACE_HEADER, "team-a")), nullValue());
    }

    /**
     * Builds a RequestMatchers backed by a Mockito-mocked logger (instance-scoped,
     * no static state) so that emitted {@link LogEntry} events can be captured and
     * asserted. The mock reports INFO and DEBUG as enabled so the diagnostic paths
     * are exercised.
     */
    private static RequestMatchers requestMatchersWithLogger(MockServerLogger logger) {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        return new RequestMatchers(configuration(), logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    private static List<LogEntry> capturedLogEntriesOfType(MockServerLogger logger, LogEntry.LogMessageType type) {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger, atLeastOnce()).logEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(logEntry -> logEntry.getType() == type)
            .collect(Collectors.toList());
    }

    @Test
    public void clearByNamespaceEmitsClearedLogOnlyWhenSomethingRemoved() {
        // given - a logger we can inspect, and a populated namespace
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(new Expectation(request().withPath("a")).withNamespace("team-a").thenRespond(response().withBody("a")), API);

        // when - clearing the populated namespace
        matchers.clearByNamespace("team-a", "correlation-id");

        // then - exactly one CLEARED entry is emitted
        assertThat(capturedLogEntriesOfType(logger, CLEARED), hasSize(1));
    }

    @Test
    public void clearByEmptyNamespaceEmitsNoClearedLog() {
        // given - a logger we can inspect, and an empty (unknown) namespace
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        Expectation global = new Expectation(request().withPath("g")).thenRespond(response().withBody("g"));
        matchers.add(global, API);

        // when - clearing a namespace with no matching expectations (idempotent teardown)
        matchers.clearByNamespace("team-empty", "correlation-id");

        // then - no spurious CLEARED entry for a no-op clear (add still logs CREATED)
        assertThat(capturedLogEntriesOfType(logger, CLEARED), is(empty()));
        // and the global expectation is untouched
        assertThat(matchers.firstMatchingExpectation(request().withPath("g")), is(global));
    }

    @Test
    public void namespaceGatedSilenceEmitsDebugDiagnostic() {
        // given - a namespaced expectation only
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(new Expectation(request().withPath("somePath")).withNamespace("team-a").thenRespond(response().withBody("a")), API);

        // when - a request in a DIFFERENT namespace matches nothing (all candidates gated out)
        assertThat(matchers.firstMatchingExpectation(requestInNamespace("team-b")), nullValue());

        // then - a DEBUG diagnostic explains the namespace-gated silence
        List<LogEntry> diagnostics = capturedLogEntriesOfType(logger, EXPECTATION_NOT_MATCHED).stream()
            .filter(logEntry -> logEntry.getLogLevel() == Level.DEBUG)
            .collect(Collectors.toList());
        assertThat(diagnostics, hasSize(1));
        assertThat(diagnostics.get(0).getMessageFormat(), containsString("namespace mismatch"));
    }

    @Test
    public void noNamespaceMismatchProducesNoNamespaceDiagnostic() {
        // given - a global expectation only
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(new Expectation(request().withPath("somePath")).thenRespond(response().withBody("g")), API);

        // when - a no-namespace request that simply does not match on path
        assertThat(matchers.firstMatchingExpectation(request().withPath("otherPath")), nullValue());

        // then - no namespace-mismatch diagnostic (nothing was gated by namespace)
        List<LogEntry> diagnostics = capturedLogEntriesOfType(logger, EXPECTATION_NOT_MATCHED).stream()
            .filter(logEntry -> logEntry.getMessageFormat() != null && logEntry.getMessageFormat().contains("namespace mismatch"))
            .collect(Collectors.toList());
        assertThat(diagnostics, is(empty()));
    }
}
