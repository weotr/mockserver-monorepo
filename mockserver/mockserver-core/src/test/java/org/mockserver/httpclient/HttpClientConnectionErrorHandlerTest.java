package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockserver.httpclient.NettyHttpClient.ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * EmbeddedChannel tests for {@link HttpClientConnectionErrorHandler} verifying that:
 * - handler removal completes future with SocketConnectionException when ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE is true
 * - handler removal completes future with null when ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE is false
 * - exception propagation completes future exceptionally
 * - already-completed futures are not modified
 */
public class HttpClientConnectionErrorHandlerTest {

    private EmbeddedChannel channel;
    private CompletableFuture<Message> responseFuture;

    @Before
    public void setUp() {
        responseFuture = new CompletableFuture<>();
        channel = new EmbeddedChannel(new HttpClientConnectionErrorHandler());
        channel.attr(RESPONSE_FUTURE).set(responseFuture);
        channel.attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE).set(true);
    }

    @After
    public void tearDown() {
        if (channel != null) {
            // HttpClientConnectionErrorHandler.exceptionCaught calls super.exceptionCaught()
            // which propagates the exception further; EmbeddedChannel stores it and re-throws
            // on finish(). We consume any stored exception before finishing.
            try {
                channel.checkException();
            } catch (Exception ignored) {
                // expected for exception-path tests
            }
            try {
                channel.finishAndReleaseAll();
            } catch (Exception ignored) {
                // exception may surface again from finishAndReleaseAll
            }
        }
    }

    @Test
    public void shouldCompleteExceptionallyWhenHandlerRemovedAndErrorFlagTrue() {
        // when - remove the handler (simulates channel closure before response)
        channel.pipeline().remove(HttpClientConnectionErrorHandler.class);

        // then
        assertTrue("response future should be done", responseFuture.isDone());
        assertTrue("response future should be exceptionally completed", responseFuture.isCompletedExceptionally());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> responseFuture.get());
        assertThat(ex.getCause(), instanceOf(SocketConnectionException.class));
        assertThat(ex.getCause().getMessage(), containsString("Channel handler removed before valid response has been received"));
    }

    @Test
    public void shouldCompleteWithNullWhenHandlerRemovedAndErrorFlagFalse() throws Exception {
        // given - set error flag to false (binary forward mode, no response expected)
        channel.attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE).set(false);

        // when
        channel.pipeline().remove(HttpClientConnectionErrorHandler.class);

        // then
        assertTrue("response future should be done", responseFuture.isDone());
        assertFalse("response future should NOT be exceptionally completed", responseFuture.isCompletedExceptionally());
        assertNull("response should be null", responseFuture.get());
    }

    @Test
    public void shouldNotModifyAlreadyCompletedFutureOnHandlerRemoval() throws Exception {
        // given - future already completed normally
        org.mockserver.model.HttpResponse earlyResponse = org.mockserver.model.HttpResponse.response().withStatusCode(200);
        responseFuture.complete(earlyResponse);

        // when
        channel.pipeline().remove(HttpClientConnectionErrorHandler.class);

        // then
        assertThat(responseFuture.get(), is(earlyResponse));
    }

    @Test
    public void shouldCompleteExceptionallyOnExceptionCaught() {
        // given
        RuntimeException cause = new RuntimeException("upstream connection failed");

        // when
        channel.pipeline().fireExceptionCaught(cause);

        // then
        assertTrue("response future should be done", responseFuture.isDone());
        assertTrue("response future should be exceptionally completed", responseFuture.isCompletedExceptionally());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> responseFuture.get());
        assertThat(ex.getCause(), is(cause));
    }

    @Test
    public void shouldNotModifyAlreadyCompletedFutureOnException() throws Exception {
        // given - future already completed
        org.mockserver.model.HttpResponse earlyResponse = org.mockserver.model.HttpResponse.response().withStatusCode(201);
        responseFuture.complete(earlyResponse);

        // when
        channel.pipeline().fireExceptionCaught(new RuntimeException("late error"));

        // then - original result preserved
        assertThat(responseFuture.get(), is(earlyResponse));
    }
}
