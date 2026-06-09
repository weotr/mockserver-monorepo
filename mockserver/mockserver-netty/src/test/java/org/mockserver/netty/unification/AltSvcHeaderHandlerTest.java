package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

/**
 * Unit tests for {@link AltSvcHeaderHandler}.
 * <p>
 * Uses an EmbeddedChannel to simulate outbound writes and verify that the
 * Alt-Svc header is correctly added (or not) to MockServer HttpResponse objects.
 */
public class AltSvcHeaderHandlerTest {

    @Test
    public void shouldAddAltSvcHeaderToResponse() {
        // given
        AltSvcHeaderHandler handler = new AltSvcHeaderHandler(8443, 86400);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        HttpResponse response = response().withStatusCode(200);

        // when
        channel.writeOutbound(response);

        // then
        HttpResponse written = channel.readOutbound();
        assertThat(written.getFirstHeader("alt-svc"), is("h3=\":8443\"; ma=86400"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotClobberExistingAltSvcHeader() {
        // given
        AltSvcHeaderHandler handler = new AltSvcHeaderHandler(8443, 86400);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("alt-svc", "h3=\":9443\"; ma=3600");

        // when
        channel.writeOutbound(response);

        // then
        HttpResponse written = channel.readOutbound();
        assertThat("user-set Alt-Svc should be preserved",
            written.getFirstHeader("alt-svc"), is("h3=\":9443\"; ma=3600"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldPassNonHttpResponseObjectsThrough() {
        // given
        AltSvcHeaderHandler handler = new AltSvcHeaderHandler(8443, 86400);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String plainMessage = "not an HttpResponse";

        // when
        channel.writeOutbound(plainMessage);

        // then
        Object written = channel.readOutbound();
        assertThat(written, is(plainMessage));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldUseConfiguredPortAndMaxAge() {
        // given
        AltSvcHeaderHandler handler = new AltSvcHeaderHandler(443, 3600);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        HttpResponse response = response().withStatusCode(200);

        // when
        channel.writeOutbound(response);

        // then
        HttpResponse written = channel.readOutbound();
        assertThat(written.getFirstHeader("alt-svc"), is("h3=\":443\"; ma=3600"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldBeSharableAcrossMultipleChannels() {
        // given - single handler instance shared across two channels
        AltSvcHeaderHandler handler = new AltSvcHeaderHandler(8443, 86400);
        EmbeddedChannel channel1 = new EmbeddedChannel(handler);
        EmbeddedChannel channel2 = new EmbeddedChannel(handler);

        // when
        channel1.writeOutbound(response().withStatusCode(200));
        channel2.writeOutbound(response().withStatusCode(404));

        // then - both channels should have the header
        HttpResponse written1 = channel1.readOutbound();
        HttpResponse written2 = channel2.readOutbound();
        assertThat(written1.getFirstHeader("alt-svc"), is("h3=\":8443\"; ma=86400"));
        assertThat(written2.getFirstHeader("alt-svc"), is("h3=\":8443\"; ma=86400"));
        channel1.finishAndReleaseAll();
        channel2.finishAndReleaseAll();
    }
}
