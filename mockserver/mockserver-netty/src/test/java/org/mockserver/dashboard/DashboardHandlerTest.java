package org.mockserver.dashboard;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.model.HttpResponse;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.HttpRequest.request;

/**
 * Regression test for issue #2347: the dashboard rendered a white page when the
 * JAR was built on a UTF-8 platform (macOS) but run on a platform whose default
 * charset is not UTF-8 (Windows, where the legacy default is windows-1252). The
 * dashboard's static assets are always written to the wire as UTF-8 (the
 * Content-Type carries {@code charset=UTF-8}), but the Content-Length header was
 * computed with the JVM default charset. For an asset containing multi-byte
 * characters the two byte counts differ, so the browser truncated the bundle and
 * the dashboard failed to render.
 */
public class DashboardHandlerTest {

    @Test
    public void contentLengthMatchesUtf8BodyLengthForMultiByteAsset() throws Exception {
        // given - a dashboard asset that contains multi-byte UTF-8 characters
        HttpResponse response = renderDashboardResource("/multibyte-test.js");

        // then - the asset was found and served
        String body = response.getBodyAsString();
        assertThat(body, is(notNullValue()));

        // and - the fixture genuinely exercises the multi-byte path: its UTF-8 byte
        // length exceeds its char count, so a charset mismatch would be observable
        int utf8ByteLength = body.getBytes(UTF_8).length;
        assertThat(utf8ByteLength, is(greaterThan(body.length())));

        // and - the declared Content-Length equals the number of bytes actually written
        // to the wire (UTF-8), independent of the JVM default charset. This is the exact
        // invariant that was violated on non-UTF-8 platforms before the #2347 fix.
        long declaredContentLength = Long.parseLong(response.getFirstHeader(CONTENT_LENGTH.toString()));
        assertThat(declaredContentLength, is((long) utf8ByteLength));
    }

    /**
     * Regression test for issue #2358: the dashboard references {@code favicon.svg}, but the
     * {@code svg} extension had no entry in the MIME map, so {@code MIME_MAP.get("svg")} returned
     * null and the served response carried a {@code Content-Type} header with a null value. That
     * null value crashed Netty's header encoder ("NullPointerException: value") when the response
     * was written, so the asset failed to load. The Content-Type must now be a valid, non-null
     * {@code image/svg+xml}.
     */
    @Test
    public void servesSvgAssetWithNonNullSvgContentType() throws Exception {
        // given - an SVG dashboard asset (matches the real favicon.svg)
        HttpResponse response = renderDashboardResource("/favicon-fixture.svg");

        // then - the asset was found and served
        assertThat(response.getBodyAsString(), is(notNullValue()));

        // and - the Content-Type is a valid, non-null SVG media type (it was null before the fix)
        String contentType = response.getFirstHeader(CONTENT_TYPE.toString());
        assertThat(contentType, is(notNullValue()));
        assertThat(contentType, equalTo("image/svg+xml"));
    }

    private HttpResponse renderDashboardResource(String resourceSuffix) throws Exception {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        ChannelFuture channelFuture = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

        new DashboardHandler().renderDashboard(
            ctx,
            request().withMethod("GET").withPath(PATH_PREFIX + "/dashboard" + resourceSuffix).withKeepAlive(false)
        );

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        assertThat(captor.getValue(), is(instanceOf(HttpResponse.class)));
        return (HttpResponse) captor.getValue();
    }
}
