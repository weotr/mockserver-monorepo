package org.mockserver.mappers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
/**
 * @author jamesdbloom
 */
public class MockServerHttpResponseToHttpServletResponseEncoderBasicMappingTest {

    @Test
    public void shouldMapHttpResponseToHttpServletResponse() throws UnsupportedEncodingException {
        // given
        // - an HttpResponse
        HttpResponse httpResponse = new HttpResponse();
        httpResponse.withStatusCode(HttpStatusCode.OK_200.code());
        httpResponse.withReasonPhrase("randomReason");
        httpResponse.withBody("somebody");
        httpResponse.withHeaders(new Header("headerName1", "headerValue1"), new Header("headerName2", "headerValue2"));
        httpResponse.withCookies(new Cookie("cookieName1", "cookieValue1"), new Cookie("cookieName2", "cookieValue2"));
        // - an HttpServletResponse
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();

        // when
        new MockServerHttpResponseToHttpServletResponseEncoder(new MockServerLogger()).mapMockServerResponseToHttpServletResponse(httpResponse, httpServletResponse);

        // then
        assertThat(httpServletResponse.getStatus(), is(HttpStatusCode.OK_200.code()));
        assertThat(httpServletResponse.getContentAsString(), is("somebody"));
        assertThat(httpServletResponse.getHeader("headerName1"), is("headerValue1"));
        assertThat(httpServletResponse.getHeader("headerName2"), is("headerValue2"));
        assertThat(httpServletResponse.getHeaders("Set-Cookie"), is(Arrays.asList(
            "cookieName1=cookieValue1",
            "cookieName2=cookieValue2"
        )));
    }

    @Test(expected = RuntimeException.class)
    public void shouldHandleExceptionWhenReadingBody() throws IOException {
        // given
        // - an HttpResponse
        HttpResponse httpResponse = new HttpResponse();
        httpResponse.withStatusCode(HttpStatusCode.OK_200.code());
        httpResponse.withBody("somebody");
        httpResponse.withHeaders(new Header("headerName1", "headerValue1"), new Header("headerName2", "headerValue2"));
        httpResponse.withCookies(new Cookie("cookieName1", "cookieValue1"), new Cookie("cookieName2", "cookieValue2"));
        // - an HttpServletResponse
        HttpServletResponse httpServletResponse = mock(HttpServletResponse.class);
        when(httpServletResponse.getOutputStream()).thenThrow(new IOException("TEST EXCEPTION"));

        // when
        new MockServerHttpResponseToHttpServletResponseEncoder(new MockServerLogger()).mapMockServerResponseToHttpServletResponse(httpResponse, httpServletResponse);
    }
}
