package org.mockserver.log.model;

import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests that the lazily-materialized cached fields of {@link LogEntry}
 * ({@code httpUpdatedRequests}, {@code httpUpdatedResponse}, {@code message} and
 * the cached {@code hashCode}) are invalidated by {@link LogEntry#clear()} and by
 * the setters that feed them.
 * <p>
 * This matters because {@link LogEntry} instances are reused via the Disruptor ring
 * buffer ({@link LogEntry#translateTo(LogEntry, long)} reuses slots and calls
 * {@code clear()}). Without invalidation, stale redacted/templated request/response
 * data or a stale cached {@code hashCode} could bleed from a previous logical entry
 * into a reused slot — and because {@link LogEntry#equals(Object)} short-circuits on
 * {@link LogEntry#hashCode()}, a stale hash can make two equal entries compare unequal.
 *
 * @author jamesdbloom
 */
public class LogEntryTest {

    @Test
    public void shouldReturnFreshUpdatedRequestsAfterClearAndRepopulate() {
        // given - a LogEntry whose updated-requests cache has been materialized
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/original").withBody("original-body"));
        RequestDefinition[] firstUpdated = logEntry.getHttpUpdatedRequests();
        assertThat(firstUpdated, is(arrayWithSize(1)));
        assertThat(firstUpdated[0].toString(), containsString("/original"));

        // when - the slot is cleared and repopulated with a different request
        logEntry.clear();
        logEntry.setHttpRequest(request().withPath("/replacement").withBody("replacement-body"));

        // then - the getter returns the NEW value, not the stale cached one
        RequestDefinition[] secondUpdated = logEntry.getHttpUpdatedRequests();
        assertThat(secondUpdated, is(arrayWithSize(1)));
        assertThat(secondUpdated[0].toString(), containsString("/replacement"));
        assertThat(secondUpdated[0].toString(), not(containsString("/original")));
    }

    @Test
    public void shouldReturnFreshUpdatedRequestsAfterSetterMutation() {
        // given - cache materialized via getter
        LogEntry logEntry = new LogEntry()
            .setHttpRequest(request().withPath("/original"));
        assertThat(logEntry.getHttpUpdatedRequests()[0].toString(), containsString("/original"));

        // when - the underlying request is changed via the setter (no clear)
        logEntry.setHttpRequest(request().withPath("/changed"));

        // then - the setter invalidated the cache, so the new value is returned
        assertThat(logEntry.getHttpUpdatedRequests()[0].toString(), containsString("/changed"));
        assertThat(logEntry.getHttpUpdatedRequests()[0].toString(), not(containsString("/original")));
    }

    @Test
    public void shouldReturnFreshUpdatedResponseAfterClearAndRepopulate() {
        // given - a LogEntry whose updated-response cache has been materialized
        LogEntry logEntry = new LogEntry()
            .setHttpResponse(response().withBody("original-response"));
        HttpResponse firstUpdated = logEntry.getHttpUpdatedResponse();
        assertThat(firstUpdated.getBodyAsString(), containsString("original-response"));

        // when - the slot is cleared and repopulated with a different response
        logEntry.clear();
        logEntry.setHttpResponse(response().withBody("replacement-response"));

        // then - the getter returns the NEW value, not the stale cached one
        HttpResponse secondUpdated = logEntry.getHttpUpdatedResponse();
        assertThat(secondUpdated.getBodyAsString(), containsString("replacement-response"));
        assertThat(secondUpdated.getBodyAsString(), not(containsString("original-response")));
    }

    @Test
    public void shouldReturnFreshUpdatedResponseAfterSetterMutation() {
        // given - cache materialized via getter
        LogEntry logEntry = new LogEntry()
            .setHttpResponse(response().withBody("original-response"));
        assertThat(logEntry.getHttpUpdatedResponse().getBodyAsString(), containsString("original-response"));

        // when - the underlying response is changed via the setter (no clear)
        logEntry.setHttpResponse(response().withBody("changed-response"));

        // then - the setter invalidated the cache, so the new value is returned
        assertThat(logEntry.getHttpUpdatedResponse().getBodyAsString(), containsString("changed-response"));
        assertThat(logEntry.getHttpUpdatedResponse().getBodyAsString(), not(containsString("original-response")));
    }

    @Test
    public void shouldReturnFreshMessageAfterClearAndRepopulate() {
        // given - a LogEntry whose message cache has been materialized
        LogEntry logEntry = new LogEntry().setMessageFormat("first message");
        assertThat(logEntry.getMessage(), is("first message"));

        // when - the slot is cleared and repopulated with a different message format
        logEntry.clear();
        logEntry.setMessageFormat("second message");

        // then - the getter returns the NEW value, not the stale cached one
        assertThat(logEntry.getMessage(), is("second message"));
    }

    @Test
    public void shouldReturnFreshMessageAfterSetterMutation() {
        // given - cache materialized via getter
        LogEntry logEntry = new LogEntry().setMessageFormat("first message");
        assertThat(logEntry.getMessage(), is("first message"));

        // when - the message format is changed via the setter (no clear)
        logEntry.setMessageFormat("updated message");

        // then - the setter invalidated the cache, so the new value is returned
        assertThat(logEntry.getMessage(), is("updated message"));
    }

    @Test
    public void shouldRecomputeHashCodeAfterClearAndRepopulate() {
        // given - two distinct logical entries
        LogEntry first = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("first")
            .setHttpRequest(request().withPath("/first"));
        LogEntry second = new LogEntry()
            .setEpochTime(2000L)
            .setMessageFormat("second")
            .setHttpRequest(request().withPath("/second"));

        // and - a reusable slot whose hashCode is materialized as the first entry
        LogEntry reused = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("first")
            .setHttpRequest(request().withPath("/first"));
        int firstHash = reused.hashCode();
        assertThat(firstHash, is(first.hashCode()));

        // when - the slot is cleared and repopulated to mirror the second entry
        reused.clear();
        reused
            .setEpochTime(2000L)
            .setMessageFormat("second")
            .setHttpRequest(request().withPath("/second"));

        // then - hashCode is recomputed for the new state (no stale hash)
        assertThat(reused.hashCode(), is(second.hashCode()));
        assertThat(reused.hashCode(), is(not(firstHash)));
        // and - equals reflects the new state (equals short-circuits on hashCode)
        assertThat(reused.equals(second), is(true));
        assertThat(reused.equals(first), is(false));
    }

    @Test
    public void shouldRecomputeHashCodeAfterSetterMutation() {
        // given - an entry whose hashCode has been materialized
        LogEntry logEntry = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("before");
        int beforeHash = logEntry.hashCode();

        // when - an equality-relevant field is mutated via a setter (no clear)
        logEntry.setMessageFormat("after");

        // then - hashCode is recomputed, matching a freshly-built equivalent entry
        LogEntry equivalent = new LogEntry()
            .setEpochTime(1000L)
            .setMessageFormat("after");
        assertThat(logEntry.hashCode(), is(equivalent.hashCode()));
        assertThat(logEntry.hashCode(), is(not(beforeHash)));
        assertThat(logEntry.equals(equivalent), is(true));
    }
}
