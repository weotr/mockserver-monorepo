package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.metrics.MetricsLock;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LlmChaosProfile;
import org.mockserver.model.Provider;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behavioural tests for strict structured-output ENFORCEMENT wired through the
 * {@link HttpActionHandler} LLM_RESPONSE dispatch (the new control-flow added alongside the
 * existing chaos {@code preEmptiveErrorResponse} mechanism). Uses the real
 * {@link HttpLlmResponseActionHandler} (constructed inside {@code HttpActionHandler}) so the
 * dispatch wiring — chaos-vs-enforcement priority, the streaming completion being forced down
 * the non-streaming error path, and the WAR {@code ctx == null} path — is exercised end-to-end.
 */
public class HttpActionHandlerLlmEnforcementTest {

    private static final String PERSON_SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}},\"required\":[\"name\",\"age\"]}";

    private static Scheduler scheduler;
    private ResponseWriter mockResponseWriter;
    private MockServerLogger mockServerLogger;
    private HttpState mockHttpStateHandler;
    private HttpActionHandler actionHandler;

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    @AfterClass
    public static void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Before
    public void setupMocks() {
        Metrics.resetAdditionalMetricsForTesting();
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        Configuration configuration = configuration().logLevel(Level.INFO).metricsEnabled(true);

        mockServerLogger = mock(MockServerLogger.class);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        mockResponseWriter = mock(ResponseWriter.class);

        mockHttpStateHandler = mock(HttpState.class);
        when(mockHttpStateHandler.getMockServerLogger()).thenReturn(mockServerLogger);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);
    }

    private HttpResponse dispatch(HttpRequest request, Expectation expectation, ChannelHandlerContext ctx) {
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        actionHandler.processAction(request, mockResponseWriter, ctx, new HashSet<>(), false, true);
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    @Test
    public void enforcementOffNonConformantBodyIsWrittenUnchanged() {
        // given — outputSchema declared but enforcement NOT enabled: the non-conforming body
        // is delivered normally (fail-soft, 200) with the diagnostic header
        HttpRequest request = request("/v1/messages");
        Expectation expectation = new Expectation(request).thenRespondWithLlm(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withModel("claude-sonnet-4-20250514")
                .withCompletion(Completion.completion()
                    .withText("{\"name\":\"Ada\"}")
                    .withOutputSchema(PERSON_SCHEMA)));

        // when
        HttpResponse written = dispatch(request, expectation, null);

        // then — normal 200 body, NOT an enforcement error
        assertThat(written.getStatusCode(), is(200));
        assertThat(written.getBodyAsString(), containsString("Ada"));
        assertThat(written.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), not(is("")));
    }

    @Test
    public void enforcementOnNonConformantNonStreamingYields502() {
        // given — enforcement ON, non-streaming, non-conforming body (missing "age")
        HttpRequest request = request("/v1/messages");
        Expectation expectation = new Expectation(request).thenRespondWithLlm(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withModel("claude-sonnet-4-20250514")
                .withCompletion(Completion.completion()
                    .withText("{\"name\":\"Ada\"}")
                    .withOutputSchema(PERSON_SCHEMA)
                    .enforceOutputSchema()));

        // when
        HttpResponse written = dispatch(request, expectation, null);

        // then — fails loudly with the provider-correct enforcement error, not the body
        assertThat(written.getStatusCode(), is(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_ENFORCEMENT_STATUS));
        assertThat(written.getBodyAsString(), containsString("api_error"));
        assertThat(written.getBodyAsString(), not(containsString("Ada")));
    }

    @Test
    public void enforcementOnNonConformantStreamingNeverBeginsStreamingAndYields502() {
        // given — enforcement ON, STREAMING completion, non-conforming body, and ctx == null
        // (the WAR/non-ctx path). Without enforcement a streaming completion with ctx == null
        // takes the "501 SSE not supported in WAR" branch; the enforcement error must instead
        // force the non-streaming error path so a strict stream never begins.
        HttpRequest request = request("/v1/messages");
        Expectation expectation = new Expectation(request).thenRespondWithLlm(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withModel("claude-sonnet-4-20250514")
                .withCompletion(Completion.completion()
                    .withText("{\"name\":\"Ada\"}")
                    .withOutputSchema(PERSON_SCHEMA)
                    .streaming()
                    .enforceOutputSchema()));

        // when
        HttpResponse written = dispatch(request, expectation, null);

        // then — the 502 enforcement error is written (NOT the 501 WAR-streaming branch),
        // proving streaming never begins
        assertThat(written.getStatusCode(), is(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_ENFORCEMENT_STATUS));
        assertThat(written.getBodyAsString(), containsString("api_error"));
    }

    @Test
    public void enforcementOnConformantBodyIsDeliveredNormally() {
        // given — enforcement ON but the body conforms
        HttpRequest request = request("/v1/messages");
        Expectation expectation = new Expectation(request).thenRespondWithLlm(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withModel("claude-sonnet-4-20250514")
                .withCompletion(Completion.completion()
                    .withText("{\"name\":\"Ada\",\"age\":36}")
                    .withOutputSchema(PERSON_SCHEMA)
                    .enforceOutputSchema()));

        // when
        HttpResponse written = dispatch(request, expectation, null);

        // then — normal 200 response, no enforcement error, no diagnostic header
        assertThat(written.getStatusCode(), is(200));
        assertThat(written.getBodyAsString(), containsString("Ada"));
        assertThat(written.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
    }

    @Test
    public void chaosErrorTakesPriorityOverEnforcement() {
        // given — both a chaos error (always-fires 503) and enforcement-on non-conforming body
        HttpRequest request = request("/v1/messages");
        Expectation expectation = new Expectation(request).thenRespondWithLlm(
            HttpLlmResponse.llmResponse()
                .withProvider(Provider.ANTHROPIC)
                .withModel("claude-sonnet-4-20250514")
                .withChaos(LlmChaosProfile.llmChaosProfile().withErrorStatus(503).withErrorProbability(1.0))
                .withCompletion(Completion.completion()
                    .withText("{\"name\":\"Ada\"}")
                    .withOutputSchema(PERSON_SCHEMA)
                    .enforceOutputSchema()));

        // when
        HttpResponse written = dispatch(request, expectation, null);

        // then — the chaos error (503) wins, not the enforcement error (502)
        assertThat(written.getStatusCode(), is(503));
        assertThat(written.getStatusCode(), not(is(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_ENFORCEMENT_STATUS)));
    }
}
