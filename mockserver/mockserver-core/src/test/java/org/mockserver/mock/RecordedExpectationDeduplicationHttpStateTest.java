package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
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
 * Behavioural tests for the opt-in {@code deduplicateRecordedExpectations}
 * configuration flag wired into the RECORDED_EXPECTATIONS retrieve path. Uses a
 * per-instance {@link Configuration} so it never mutates global
 * {@code ConfigurationProperties} state (and therefore needs no two-phase
 * Surefire registration).
 *
 * @author jamesdbloom
 */
public class RecordedExpectationDeduplicationHttpStateTest {

    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());

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

    private void recordUsersCalls(HttpState httpState) {
        // two structurally-identical recorded calls /users/1 and /users/2 with the same response shape
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/users/1").withMethod("GET"))
                .setHttpResponse(response("ok"))
                .setExpectation(new Expectation(request("/users/1").withMethod("GET"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("ok")))
        );
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/users/2").withMethod("GET"))
                .setHttpResponse(response("ok"))
                .setExpectation(new Expectation(request("/users/2").withMethod("GET"), Times.once(), TimeToLive.unlimited(), 0).withId("key_two").thenRespond(response("ok")))
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
    public void shouldReturnRecordedExpectationsVerbatimWhenFlagOff() {
        // given - default flag is OFF
        Configuration configuration = configuration();
        assertThat(configuration.deduplicateRecordedExpectations(), is(false));
        HttpState httpState = newHttpState(configuration);
        recordUsersCalls(httpState);

        // when
        HttpResponse response = retrieveRecorded(httpState);

        // then - both concrete paths are returned, no template applied
        String body = response.getBodyAsString();
        assertThat(body, containsString("/users/1"));
        assertThat(body, containsString("/users/2"));
        assertThat(body, not(containsString("{id}")));
        // two expectations retrieved verbatim
        assertThat(expectationSerializer.deserializeArray(body, true).length, is(2));
    }

    @Test
    public void shouldDeduplicateAndTemplatizeRecordedExpectationsWhenFlagOn() {
        // given - flag explicitly ON via the instance Configuration
        Configuration configuration = configuration().deduplicateRecordedExpectations(true);
        assertThat(configuration.deduplicateRecordedExpectations(), is(true));
        HttpState httpState = newHttpState(configuration);
        recordUsersCalls(httpState);

        // when
        HttpResponse response = retrieveRecorded(httpState);

        // then - the two id calls collapse into a single /users/{id} expectation
        String body = response.getBodyAsString();
        assertThat(body, containsString("/users/{id}"));
        assertThat(body, not(containsString("/users/1")));
        assertThat(body, not(containsString("/users/2")));
        assertThat(expectationSerializer.deserializeArray(body, true).length, is(1));
    }
}
