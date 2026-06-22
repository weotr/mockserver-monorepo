package org.mockserver.server;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToHttpServletResponseEncoder;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.servlet.responsewriter.ServletResponseWriter;
import org.springframework.mock.web.MockHttpServletResponse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAllResponses;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * @author jamesdbloom
 */
public class ServletResponseWriterTest {

    @Mock
    private MockServerHttpResponseToHttpServletResponseEncoder mockServerResponseToHttpServletResponseEncoder;
    @InjectMocks
    private ServletResponseWriter servletResponseWriter;
    private MockHttpServletResponse httpServletResponse;

    @Before
    public void setupTestFixture() {
        httpServletResponse = new MockHttpServletResponse();
        servletResponseWriter = new ServletResponseWriter(configuration(), new MockServerLogger(), httpServletResponse);
        openMocks(this);
    }

    @Test
    public void shouldWriteBasicResponse() {
        // given
        HttpRequest request = request("some_request");
        HttpResponse response = response("some_response");

        // when
        servletResponseWriter.writeResponse(request, response, false);

        // then
        verify(mockServerResponseToHttpServletResponseEncoder).mapMockServerResponseToHttpServletResponse(
            response("some_response")
                .withHeader("connection", "close"),
            httpServletResponse
        );
    }

    @Test
    public void shouldWriteContentTypeForStringBody() {
        // given
        HttpRequest request = request("some_request");
        HttpResponse response = response().withBody("some_response", UTF_8);

        // when
        servletResponseWriter.writeResponse(request, response, false);

        // then
        verify(mockServerResponseToHttpServletResponseEncoder).mapMockServerResponseToHttpServletResponse(
            response()
                .withHeader("connection", "close")
                .withBody("some_response", UTF_8),
            httpServletResponse
        );
    }

    @Test
    public void shouldWriteContentTypeForJsonBody() {
        // given
        HttpRequest request = request("some_request");
        HttpResponse response = response().withBody(json("\"some_response\""));

        // when
        servletResponseWriter.writeResponse(request, response, false);

        // then
        verify(mockServerResponseToHttpServletResponseEncoder).mapMockServerResponseToHttpServletResponse(
            response()
                .withHeader("connection", "close")
                .withBody(json("\"some_response\"")),
            httpServletResponse
        );
    }

    @Test
    public void shouldWriteNullResponse() {
        // given
        HttpRequest request = request("some_request");

        // when
        servletResponseWriter.writeResponse(request, null, false);

        // then
        verify(mockServerResponseToHttpServletResponseEncoder).mapMockServerResponseToHttpServletResponse(
            notFoundResponse()
                .withHeader("connection", "close"),
            httpServletResponse
        );
    }

    @Test
    public void shouldWriteAddCORSHeaders() {
        boolean enableCORSForAllResponses = enableCORSForAllResponses();
        try {
            // given
            enableCORSForAllResponses(true);
            HttpRequest request = request("some_request");
            HttpResponse response = response("some_response");

            // when
            servletResponseWriter.writeResponse(request, response, false);

            // then
            verify(mockServerResponseToHttpServletResponseEncoder).mapMockServerResponseToHttpServletResponse(
                response("some_response")
                    .withHeader("access-control-allow-origin", "*")
                    .withHeader("access-control-allow-methods", "CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE")
                    .withHeader("access-control-allow-headers", "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization")
                    .withHeader("access-control-expose-headers", "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization")
                    .withHeader("access-control-max-age", "0")
                    .withHeader("access-control-allow-credentials", "false")
                    .withHeader("connection", "close"),
                httpServletResponse
            );
        } finally {
            enableCORSForAllResponses(enableCORSForAllResponses);
        }
    }

    @Test
    public void shouldStampConfiguredDefaultResponseHeadersOnMockResponse() {
        // given - per-instance configuration (no global state mutation); a real writer/encoder so
        // the headers actually land on the MockHttpServletResponse
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletResponseWriter writer = new ServletResponseWriter(
            configuration().defaultResponseHeaders("Server=MockServer|X-Trace-Id=abc123"),
            new MockServerLogger(),
            servletResponse
        );

        // when - mock response (apiResponse == false)
        writer.writeResponse(request("some_request"), response("some_response"), false);

        // then
        assertThat(servletResponse.getHeader("Server"), is("MockServer"));
        assertThat(servletResponse.getHeader("X-Trace-Id"), is("abc123"));
    }

    @Test
    public void shouldNotOverwriteResponseHeaderWithConfiguredDefault() {
        // given - the mock response already sets Server, default also configures Server
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletResponseWriter writer = new ServletResponseWriter(
            configuration().defaultResponseHeaders("Server=MockServer|X-Trace-Id=abc123"),
            new MockServerLogger(),
            servletResponse
        );

        // when
        writer.writeResponse(request("some_request"), response("some_response").withHeader("Server", "ExplicitValue"), false);

        // then - explicit Server wins (add-if-absent), only the absent default is added
        assertThat(servletResponse.getHeaders("Server"), is(java.util.Collections.singletonList("ExplicitValue")));
        assertThat(servletResponse.getHeader("X-Trace-Id"), is("abc123"));
    }

    @Test
    public void shouldNotAddAnyHeadersWhenDefaultResponseHeadersEmpty() {
        // given - default (no defaultResponseHeaders configured) => behaviour unchanged
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletResponseWriter writer = new ServletResponseWriter(
            configuration(),
            new MockServerLogger(),
            servletResponse
        );

        // when
        writer.writeResponse(request("some_request"), response("some_response"), false);

        // then - no default headers stamped (Server header absent)
        assertThat(servletResponse.getHeader("Server"), is(org.hamcrest.Matchers.nullValue()));
        assertThat(servletResponse.getHeader("X-Trace-Id"), is(org.hamcrest.Matchers.nullValue()));
    }

}
