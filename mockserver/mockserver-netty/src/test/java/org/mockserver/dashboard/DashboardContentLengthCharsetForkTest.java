package org.mockserver.dashboard;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.model.HttpResponse;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.HttpRequest.request;

/**
 * Host-INDEPENDENT regression guard for issue #2347 (dashboard white page).
 *
 * <p>The dashboard's static string assets are always written to the wire as UTF-8 (the
 * Content-Type carries {@code charset=UTF-8}). The Content-Length header must therefore be the
 * UTF-8 byte count. The #2347 bug was that the header was computed with {@code content.getBytes()}
 * — the JVM <em>default</em> charset — so on a non-UTF-8-default host (e.g. windows-1252) a
 * multi-byte asset declared the wrong (legacy single-byte) length, the browser truncated the JS
 * bundle, and the dashboard rendered a white page.
 *
 * <p>The sibling {@link DashboardHandlerTest#contentLengthMatchesUtf8BodyLengthForMultiByteAsset()}
 * test catches a {@code getBytes()} revert only on a host whose default charset is NOT UTF-8: on a
 * UTF-8-default JVM {@code getBytes()} == {@code getBytes(UTF_8)} so the revert is invisible at
 * runtime there. To make the regression catchable in CI <em>regardless of the CI host's locale</em>,
 * this class is run in a dedicated FORKED JVM whose default charset is forced to ISO-8859-1 (a
 * single-byte legacy charset, the family of windows-1252). Under that forked charset a
 * {@code getBytes()} revert yields the ISO byte count, which differs from the UTF-8 count for the
 * multi-byte fixture, so the assertion below turns RED.
 *
 * <p>The fork is configured by a dedicated {@code dashboard-charset-fork-test} surefire execution in
 * {@code mockserver-netty/pom.xml} which passes {@code -Dfile.encoding=ISO-8859-1
 * -Dsun.jnu.encoding=ISO-8859-1} and runs ONLY this class (it is excluded from the default
 * execution). The project floor is Java 17, where forcing {@code file.encoding} to a non-UTF-8
 * charset changes {@link Charset#defaultCharset()} and the guard works. The same explicit
 * {@code file.encoding} override is also honoured on several Java 18+ builds (JEP 400 only changed
 * the <em>default</em> charset, when unset, to UTF-8 — an explicit non-UTF-8 value is still applied),
 * so the guard runs there too. On any JVM where the override is NOT honoured and the default charset
 * stays UTF-8, the precondition below is not met and the test SKIPS (via {@link org.junit.Assume})
 * rather than false-passing, leaving the host-dependent {@link DashboardHandlerTest} as the backstop.
 */
public class DashboardContentLengthCharsetForkTest {

    @Test
    public void contentLengthIsUtf8ByteCountNotDefaultCharsetByteCountInForkedNonUtf8Jvm() throws Exception {
        // precondition - the fork must actually have a non-UTF-8 default charset for this guard to
        // mean anything. The dedicated surefire execution passes -Dfile.encoding=ISO-8859-1; on
        // Java 17 (and on JVMs that honour an explicit non-UTF-8 file.encoding) Charset.defaultCharset()
        // is then ISO-8859-1. On any JVM where the override is ignored and the default stays UTF-8
        // (JEP 400 behaviour) SKIP rather than asserting vacuously, since under a UTF-8 default a
        // getBytes() revert is indistinguishable from getBytes(UTF_8).
        assumeFalse(
            "forked non-UTF-8 charset not in effect on this JVM (explicit -Dfile.encoding override not "
                + "honoured) — charset-fork guard cannot run here; the host-dependent "
                + "DashboardHandlerTest remains the backstop",
            UTF_8.equals(Charset.defaultCharset()));

        // given - a dashboard asset that contains multi-byte UTF-8 characters
        HttpResponse response = renderDashboardResource("/multibyte-test.js");

        // then - the asset was found and served
        String body = response.getBodyAsString();
        assertThat(body, is(notNullValue()));

        int utf8ByteLength = body.getBytes(UTF_8).length;
        int defaultCharsetByteLength = body.getBytes(Charset.defaultCharset()).length;

        // and - under this forked non-UTF-8 charset the fixture genuinely distinguishes a UTF-8
        // encode from a default-charset encode (otherwise the guard below would be vacuous).
        assertThat(
            "fixture must encode to a different byte length under UTF-8 vs the forked default charset "
                + Charset.defaultCharset() + " (got utf8=" + utf8ByteLength
                + ", default=" + defaultCharsetByteLength + ")",
            utf8ByteLength, is(not(defaultCharsetByteLength)));

        long declaredContentLength = Long.parseLong(response.getFirstHeader(CONTENT_LENGTH.toString()));

        // and - the declared Content-Length is the UTF-8 byte count actually written to the wire.
        // A revert of DashboardHandler to content.getBytes() (the default charset) would declare
        // defaultCharsetByteLength here, which under this forked charset differs from the UTF-8
        // count, so this assertion turns RED and the #2347 regression is caught in CI on ANY host.
        assertThat(
            "Content-Length must be the UTF-8 byte count written to the wire, not a default-charset "
                + "encode (#2347)",
            declaredContentLength, is((long) utf8ByteLength));
        assertThat(
            "Content-Length must NOT be the forked default-charset byte count (a getBytes() revert)",
            declaredContentLength, is(not((long) defaultCharsetByteLength)));
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
