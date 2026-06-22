package org.mockserver.netty.integration.mock;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the HTTP/2 wiring for general response trailers. The MockServer response
 * mapper emits a {@code LastHttpContent} carrying the trailers; Netty's
 * {@link Http2StreamFrameToHttpObjectCodec} (the same bidirectional codec used on the
 * MockServer h2 multiplex pipeline) converts those trailing headers into a trailing
 * HTTP/2 HEADERS frame with {@code endStream=true}. This is the protocol-level proof
 * that trailers reach the wire as an h2 trailer frame.
 */
public class ResponseTrailersHttp2ConversionTest {

    @Test
    public void shouldConvertTrailingHeadersToHttp2TrailerFrame() {
        // given -- map a MockServer response with trailers to Netty HTTP/1 objects
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("hello")
            .withTrailer("x-checksum", "abc123")
            .withTrailer("x-signature", "deadbeef");

        List<DefaultHttpObject> nettyObjects =
            new MockServerHttpResponseToFullHttpResponse(new MockServerLogger())
                .mapMockServerResponseToNettyResponse(httpResponse);

        // when -- run them outbound through the server-mode h2 stream codec
        EmbeddedChannel channel = new EmbeddedChannel(new Http2StreamFrameToHttpObjectCodec(true));
        for (DefaultHttpObject object : nettyObjects) {
            channel.writeOutbound(object);
        }
        channel.finish();

        // then -- collect the produced HTTP/2 stream frames
        List<Http2HeadersFrame> headersFrames = new ArrayList<>();
        List<Http2DataFrame> dataFrames = new ArrayList<>();
        Object frame;
        while ((frame = channel.readOutbound()) != null) {
            if (frame instanceof Http2HeadersFrame) {
                headersFrames.add((Http2HeadersFrame) frame);
            } else if (frame instanceof Http2DataFrame) {
                dataFrames.add((Http2DataFrame) frame);
            } else if (frame instanceof Http2StreamFrame) {
                ReferenceCountUtil.release(frame);
            }
        }

        try {
            // initial HEADERS frame (status) is NOT end-of-stream
            assertThat(headersFrames, hasSize(2));
            Http2HeadersFrame initial = headersFrames.get(0);
            assertThat(initial.headers().status().toString(), is("200"));
            assertThat(initial.isEndStream(), is(false));

            // trailing HEADERS frame carries the trailers and ends the stream
            Http2HeadersFrame trailers = headersFrames.get(1);
            Http2Headers h = trailers.headers();
            assertThat(trailers.isEndStream(), is(true));
            assertThat(h.get("x-checksum") == null ? null : h.get("x-checksum").toString(), is("abc123"));
            assertThat(h.get("x-signature") == null ? null : h.get("x-signature").toString(), is("deadbeef"));
            // status pseudo-header must not appear in a trailer frame
            assertThat(h.status(), is(nullValue()));
        } finally {
            dataFrames.forEach(ReferenceCountUtil::release);
        }
    }
}
