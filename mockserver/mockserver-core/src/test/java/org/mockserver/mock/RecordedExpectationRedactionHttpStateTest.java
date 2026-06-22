package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RetrieveType;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for the opt-in {@code redactSecretsInRecordedExpectations}
 * configuration flag wired into the RECORDED_EXPECTATIONS retrieve path. Proves
 * that proxied {@code Authorization} / {@code Cookie} / {@code x-api-key} /
 * bearer-token values are masked before recorded expectations are returned (and
 * therefore before they are generated as client code or persisted to JSON).
 * <p>
 * Uses a per-instance {@link Configuration} so it never mutates global
 * {@code ConfigurationProperties} state (and therefore needs no two-phase
 * Surefire registration).
 *
 * @author jamesdbloom
 */
public class RecordedExpectationRedactionHttpStateTest {

    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());

    private static final String BEARER_TOKEN = "Bearer eyJhbGciOiJIUzI1Ni1234567890SECRET";
    private static final String API_KEY = "sk-secret-api-key-value-123";
    private static final String COOKIE = "session=super-secret-session-id";

    private static class FakeResponseWriter extends ResponseWriter {
        public HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    private HttpState newHttpState(Configuration configuration) {
        Scheduler scheduler = mock(Scheduler.class);
        return new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    private void recordCallWithSecrets(HttpState httpState) {
        HttpRequest recordedRequest = request("/secure")
            .withMethod("GET")
            .withHeader("Authorization", BEARER_TOKEN)
            .withHeader("x-api-key", API_KEY)
            .withHeader("Cookie", COOKIE);
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(recordedRequest)
                .setHttpResponse(response("ok"))
                .setExpectation(new Expectation(recordedRequest, Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("ok")))
        );
    }

    private HttpResponse retrieveRecorded(HttpState httpState) {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        HttpRequest retrieveRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.RECORDED_EXPECTATIONS.name())
            .withBody(requestDefinitionSerializer.serialize(request()));
        boolean handle = httpState.handle(retrieveRequest, responseWriter, false);
        assertThat(handle, is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        return responseWriter.response;
    }

    @Test
    public void shouldReturnRecordedSecretsVerbatimWhenFlagOff() {
        // given - default flag is OFF
        Configuration configuration = configuration();
        assertThat(configuration.redactSecretsInRecordedExpectations(), is(false));
        HttpState httpState = newHttpState(configuration);
        recordCallWithSecrets(httpState);

        // when
        HttpResponse response = retrieveRecorded(httpState);

        // then - secrets are returned unchanged
        String body = response.getBodyAsString();
        assertThat(body, containsString(BEARER_TOKEN));
        assertThat(body, containsString(API_KEY));
        assertThat(body, containsString(COOKIE));
        assertThat(body, not(containsString(FixtureRedactor.REDACTED_PLACEHOLDER)));
    }

    @Test
    public void shouldRedactRecordedSecretsWhenFlagOn() {
        // given - flag explicitly ON via the instance Configuration
        Configuration configuration = configuration().redactSecretsInRecordedExpectations(true);
        assertThat(configuration.redactSecretsInRecordedExpectations(), is(true));
        HttpState httpState = newHttpState(configuration);
        recordCallWithSecrets(httpState);

        // when
        HttpResponse response = retrieveRecorded(httpState);

        // then - every sensitive value is masked, structure preserved
        String body = response.getBodyAsString();
        assertThat(body, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat(body, not(containsString(BEARER_TOKEN)));
        assertThat(body, not(containsString(API_KEY)));
        assertThat(body, not(containsString(COOKIE)));
        // still a single recorded expectation for the same path
        Expectation[] redacted = expectationSerializer.deserializeArray(body, true);
        assertThat(redacted.length, is(1));
        assertThat(body, containsString("/secure"));
        // replay constraints and id are preserved (not reset to unlimited / dropped)
        assertThat(redacted[0].getTimes().getRemainingTimes(), is(1));
        assertThat(redacted[0].getTimes().isUnlimited(), is(false));
        assertThat(redacted[0].getId(), is("key_one"));
    }
}
