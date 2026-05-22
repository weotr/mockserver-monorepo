package org.mockserver.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

public class StreamingAwareHttpObjectAggregatorTest {

    @Test
    public void shouldDetectSseResponse() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(true));
    }

    @Test
    public void shouldDetectSseResponseWithCharset() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(true));
    }

    @Test
    public void shouldNotDetectChunkedResponseWithoutContentLengthWhenNotSse() {
        // Regression guard for WAR deployment: Tomcat uses chunked encoding without
        // Content-Length for all servlet responses. These must NOT be detected as streaming.
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldNotDetectChunkedResponseWithContentLength() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setTransferEncodingChunked(response, true);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 100);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldDetectSseResponseWithoutChunkedEncoding() {
        // SSE responses should be detected even without explicit chunked transfer-encoding
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(true));
    }

    @Test
    public void shouldNotDetectOctetStreamAsStreaming() {
        // application/octet-stream with chunked encoding should NOT be detected as streaming
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        HttpUtil.setTransferEncodingChunked(response, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldNotDetectNormalResponseWithContentLength() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 42);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldNotDetectResponseWithNoHeaders() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(response), is(false));
    }

    @Test
    public void shouldFullyAggregateWhenDisableResponseStreamingAttributeIsSet() {
        // When DISABLE_RESPONSE_STREAMING is set on the channel (e.g. FORWARD_REPLACE),
        // the aggregator should NOT switch to streaming mode even for a streaming response.
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        StreamingAwareHttpObjectAggregator aggregator =
            new StreamingAwareHttpObjectAggregator(Integer.MAX_VALUE, configuration, null);

        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        // Set the disable attribute
        AttributeKey<Boolean> disableKey = AttributeKey.valueOf("DISABLE_RESPONSE_STREAMING");
        channel.attr(disableKey).set(true);

        // Send a streaming response head
        DefaultHttpResponse streamingHead = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        streamingHead.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(streamingHead, true);

        // Write the head - should be aggregated (not switched to streaming)
        channel.writeInbound(streamingHead);

        // Write a last content to complete aggregation
        channel.writeInbound(new DefaultLastHttpContent());

        // Should produce a FullHttpResponse (aggregated), not a bare HttpResponse
        Object outbound = channel.readInbound();
        assertThat("should produce FullHttpResponse when streaming is disabled on channel",
            outbound, instanceOf(FullHttpResponse.class));

        // Clean up
        if (outbound instanceof FullHttpResponse) {
            ((FullHttpResponse) outbound).release();
        }
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStreamWhenDisableResponseStreamingAttributeIsNotSet() {
        // When DISABLE_RESPONSE_STREAMING is NOT set, streaming responses should be detected
        Configuration configuration = new Configuration();
        configuration.streamingResponsesEnabled(true);

        // Just test the static detection method (full pipeline test is in integration tests)
        DefaultHttpResponse streamingHead = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        streamingHead.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        HttpUtil.setTransferEncodingChunked(streamingHead, true);

        assertThat(StreamingAwareHttpObjectAggregator.isStreamingResponse(streamingHead), is(true));
    }
}
