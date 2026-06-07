package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;

import javax.net.ssl.SSLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * EmbeddedChannel tests for {@link HttpClientHandler} verifying observable channel
 * behaviour: response completion, channel closure, and exception handling.
 */
public class HttpClientHandlerTest {

    private EmbeddedChannel channel;
    private CompletableFuture<Message> responseFuture;

    @Before
    public void setUp() {
        responseFuture = new CompletableFuture<>();
        channel = new EmbeddedChannel(new HttpClientHandler());
        channel.attr(RESPONSE_FUTURE).set(responseFuture);
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldCompleteResponseFutureOnChannelRead() throws Exception {
        // given
        HttpResponse response = HttpResponse.response().withStatusCode(200);

        // when
        channel.writeInbound(response);

        // then
        assertTrue("response future should be done", responseFuture.isDone());
        assertFalse("response future should not be exceptionally completed", responseFuture.isCompletedExceptionally());
        assertThat(responseFuture.get(), is(response));
    }

    @Test
    public void shouldCloseChannelAfterReceivingResponse() {
        // given
        HttpResponse response = HttpResponse.response().withStatusCode(200);

        // when
        channel.writeInbound(response);

        // then - channel should be closed (or closing)
        assertFalse("channel should be inactive after response", channel.isActive());
    }

    @Test
    public void shouldCompleteExceptionallyOnException() {
        // given
        RuntimeException cause = new RuntimeException("test error");

        // when
        channel.pipeline().fireExceptionCaught(cause);

        // then
        assertTrue("response future should be done", responseFuture.isDone());
        assertTrue("response future should be exceptionally completed", responseFuture.isCompletedExceptionally());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> responseFuture.get());
        assertThat(ex.getCause(), is(cause));
    }

    @Test
    public void shouldCloseChannelAfterException() {
        // given
        RuntimeException cause = new RuntimeException("test error");

        // when
        channel.pipeline().fireExceptionCaught(cause);

        // then
        assertFalse("channel should be inactive after exception", channel.isActive());
    }

    @Test
    public void shouldCompleteExceptionallyOnSslException() {
        // given - SSLException wrapped in DecoderException
        io.netty.handler.codec.DecoderException cause =
            new io.netty.handler.codec.DecoderException(new SSLException("handshake failure"));

        // when
        channel.pipeline().fireExceptionCaught(cause);

        // then
        assertTrue("response future should be exceptionally completed", responseFuture.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, () -> responseFuture.get());
        assertThat(ex.getCause(), is(cause));
    }

    @Test
    public void shouldCompleteExceptionallyOnConnectionResetException() {
        // given - a "Connection reset" exception (should not print stack trace but still complete exceptionally)
        RuntimeException cause = new RuntimeException("Connection reset");

        // when
        channel.pipeline().fireExceptionCaught(cause);

        // then
        assertTrue("response future should be exceptionally completed", responseFuture.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, () -> responseFuture.get());
        assertThat(ex.getCause().getMessage(), containsString("Connection reset"));
    }
}
