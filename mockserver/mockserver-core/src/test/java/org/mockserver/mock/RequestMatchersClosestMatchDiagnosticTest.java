package org.mockserver.mock;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for the closest-expectation "matched X/Y fields" diagnostic
 * emitted by {@link RequestMatchers#firstMatchingExpectation} when no expectation
 * matches and INFO logging is enabled (review item W3-2).
 *
 * <p>The two defects this pins:</p>
 * <ol>
 *   <li>The denominator must be the count of fields APPLICABLE to the request's
 *       protocol (ten for an HttpRequest), not all sixteen
 *       {@code MatchDifference.Field} enum constants (which include the
 *       DNS/binary/OpenAPI/operation fields an HttpRequest never exercises).</li>
 *   <li>The matched-field numerator must reflect EVERY applicable field, not the
 *       at-most-one difference recorded under the default fail-fast matching — so a
 *       two-field mismatch must report 8/10, never the collapsed "15/16" (or
 *       "9/10") it would degrade to if it relied on the fail-fast difference size.</li>
 * </ol>
 *
 * <p>Uses an instance {@link org.mockserver.configuration.Configuration} and a
 * Mockito-mocked logger only — no static/global state — so these tests run in the
 * parallel Surefire phase.</p>
 */
public class RequestMatchersClosestMatchDiagnosticTest {

    private static RequestMatchers requestMatchersWithLogger(MockServerLogger logger) {
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        return new RequestMatchers(configuration(), logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    private static List<LogEntry> closestMatchDiagnostics(MockServerLogger logger) {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger, atLeastOnce()).logEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(logEntry -> logEntry.getType() == EXPECTATION_NOT_MATCHED)
            .filter(logEntry -> logEntry.getMessageFormat() != null
                && logEntry.getMessageFormat().contains("closest expectation"))
            .collect(Collectors.toList());
    }

    @Test
    public void denominatorIsApplicableHttpFieldsNotFullEnum() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when - a request that matches nothing on this expectation
        matchers.firstMatchingExpectation(request().withMethod("POST").withPath("differentPath"));

        // then - the diagnostic denominator is the 10 HTTP-applicable fields, never 16
        List<LogEntry> diagnostics = closestMatchDiagnostics(logger);
        assertThat(diagnostics, hasSize(1));
        String messageFormat = diagnostics.get(0).getMessageFormat();
        assertThat(messageFormat, containsString("/10 fields"));
        assertThat(messageFormat, not(containsString("/16 fields")));
    }

    @Test
    public void numeratorReflectsAllApplicableFieldsAndIsNotFailFastCollapsed() {
        // given - an expectation that the request mismatches on TWO fields (method + path)
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when
        matchers.firstMatchingExpectation(request().withMethod("POST").withPath("differentPath"));

        // then - two applicable fields failed, so the report is 8/10. It must NOT be
        // collapsed to "9/10" (the fail-fast difference size of 1) nor inflated to "14/16".
        List<LogEntry> diagnostics = closestMatchDiagnostics(logger);
        assertThat(diagnostics, hasSize(1));
        String messageFormat = diagnostics.get(0).getMessageFormat();
        assertThat(messageFormat, containsString("matched 8/10 fields"));
    }

    @Test
    public void diagnosticReEvaluationDoesNotEmitSpuriousNotMatchedLogEvents() {
        // given - a single expectation the request mismatches on two fields
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when
        matchers.firstMatchingExpectation(request().withMethod("POST").withPath("differentPath"));

        // then - the cold-path closest-match field-count re-evaluation runs the matcher
        // a second time, but is marked suppressMatchResultLogging, so it must NOT add an
        // extra per-matcher "didn't match" entry. Only the real scan's single
        // EXPECTATION_NOT_MATCHED "didn't match" entry should be present (plus the separate
        // "closest expectation" summary, which is filtered out here).
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger, atLeastOnce()).logEvent(captor.capture());
        List<LogEntry> perMatcherNotMatched = captor.getAllValues().stream()
            .filter(logEntry -> logEntry.getType() == EXPECTATION_NOT_MATCHED)
            .filter(logEntry -> logEntry.getMessageFormat() != null
                && logEntry.getMessageFormat().startsWith("request:{}didn't match"))
            .collect(Collectors.toList());
        assertThat(perMatcherNotMatched, hasSize(1));
    }

    @Test
    public void singleFieldMismatchReportsNineOfTen() {
        // given - request differs from the expectation on exactly one field (path)
        MockServerLogger logger = mock(MockServerLogger.class);
        RequestMatchers matchers = requestMatchersWithLogger(logger);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when
        matchers.firstMatchingExpectation(request().withMethod("GET").withPath("differentPath"));

        // then
        List<LogEntry> diagnostics = closestMatchDiagnostics(logger);
        assertThat(diagnostics, hasSize(1));
        assertThat(diagnostics.get(0).getMessageFormat(), containsString("matched 9/10 fields"));
    }
}
