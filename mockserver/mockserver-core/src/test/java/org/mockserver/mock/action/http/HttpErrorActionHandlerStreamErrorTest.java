package org.mockserver.mock.action.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.junit.Test;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the stream-error fallback branches in {@link HttpErrorActionHandler} that the
 * mockserver-netty HTTP/2 integration tests do not exercise: the HTTP/1.1 connection-drop fallback and
 * the "HTTP/2 stream id unavailable" fallback.
 * <p>
 * The successful HTTP/2 stream-reset paths (connection-level and multiplex child channel) and the
 * streamError-over-dropConnection precedence are proven end-to-end against a real server pipeline in
 * {@code StreamErrorHttp2IntegrationTest} (mockserver-netty), since a true {@code Http2StreamChannel}
 * cannot be opened on a bare {@link EmbeddedChannel} (the frame codec rejects an inbound HEADERS frame
 * before the HTTP/2 connection preface / SETTINGS exchange).
 */
public class HttpErrorActionHandlerStreamErrorTest {

    @Test
    public void shouldFallBackToConnectionDropOnHttp1WhenStreamErrorSet() {
        // given - a plain HTTP/1.1 pipeline (no HTTP/2 handlers, no Http2StreamChannel)
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        HttpError httpError = HttpError.error().withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM);

        // when - applying a stream error to an HTTP/1.1 channel
        new HttpErrorActionHandler().handle(httpError, new HttpRequest(), channel.pipeline().firstContext());

        // then - there is no stream to reset, so the connection is dropped (existing HttpError behaviour)
        assertThat("HTTP/1.1 channel should be closed when a stream error has no stream to reset",
            channel.isOpen(), is(false));
    }

    @Test
    public void shouldFallBackToConnectionDropWhenStreamErrorAndDropConnectionSetOnHttp1() {
        // given - an HTTP/1.1 channel and an error with BOTH streamError and dropConnection set
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        HttpError httpError = HttpError.error()
            .withDropConnection(true)
            .withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM);

        // when
        new HttpErrorActionHandler().handle(httpError, new HttpRequest(), channel.pipeline().firstContext());

        // then - the streamError branch handles it (and on HTTP/1.1 its fallback drops the connection)
        assertThat(channel.isOpen(), is(false));
    }

    @Test
    public void shouldNotResetHttp2StreamWhenStreamIdMissing() {
        // given - a connection-level HTTP/2 pipeline but a request without a stream id (degenerate)
        EmbeddedChannel channel = new EmbeddedChannel(
            Http2FrameCodecBuilder.forServer().build(),
            new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                }
            })
        );
        HttpError httpError = HttpError.error().withStreamError(0x8L);

        // when - the request carries no stream id and the channel is not an Http2StreamChannel
        new HttpErrorActionHandler().handle(httpError, new HttpRequest(), channel.pipeline().lastContext());

        // then - it falls back to dropping the connection rather than throwing
        assertThat(channel.isOpen(), is(false));
    }
}
