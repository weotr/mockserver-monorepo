package org.mockserver.filters;

import org.junit.Test;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * @author jamesdbloom
 */
public class HopByHopHeaderFilterTest {

    @Test
    public void shouldNotForwardHopByHopHeaders() {
        // given
        HttpRequest httpRequest = new HttpRequest();
        httpRequest.withHeaders(
            new Header("some_other_header"),
            new Header("proxy-connection"),
            new Header("connection"),
            new Header("keep-alive"),
            new Header("transfer-encoding"),
            new Header("te"),
            new Header("trailer"),
            new Header("proxy-authorization"),
            new Header("proxy-authenticate"),
            new Header("upgrade")
        );

        // when
        httpRequest = new HopByHopHeaderFilter().onRequest(httpRequest);

        // then
        assertThat(httpRequest.getHeaderList().size(), is(1));
    }

    @Test
    public void shouldNotHandleNullRequest() {
        assertThat(new HopByHopHeaderFilter().onRequest(null), nullValue());
    }
}
