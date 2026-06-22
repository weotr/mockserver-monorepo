package org.mockserver.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.configuration.ClientConfiguration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.*;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Unit tests for the breakpoint matcher registration/management REST methods
 * on MockServerClient, and for the breakpoint handler interfaces and
 * BreakpointWebSocketClient message routing.
 */
public class BreakpointMatcherClientTest {

    @Mock
    private NettyHttpClient mockHttpClient;
    @Mock
    private RequestDefinitionSerializer mockRequestDefinitionSerializer;
    @Mock
    private HttpRequestSerializer mockHttpRequestSerializer;
    @Mock
    private HttpResponseSerializer mockHttpResponseSerializer;
    @InjectMocks
    private MockServerClient mockServerClient;

    @Captor
    ArgumentCaptor<HttpRequest> httpRequestArgumentCaptor;

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    @Before
    public void setupTestFixture() {
        mockServerClient = new MockServerClient("localhost", 1090);
        openMocks(this);
    }

    // -------------------------------------------------------------------
    // listBreakpointMatchers
    // -------------------------------------------------------------------

    @Test
    public void shouldListBreakpointMatchers() {
        // given
        String expectedResponse = "{\"matchers\":[]}";
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody(expectedResponse));

        // when
        String result = mockServerClient.listBreakpointMatchers();

        // then
        assertThat(result, is(expectedResponse));
        verify(mockHttpClient).sendRequest(httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest capturedRequest = httpRequestArgumentCaptor.getValue();
        assertThat(capturedRequest.getMethod(""), is("GET"));
        assertThat(capturedRequest.getPath().getValue(), containsString("breakpoint/matchers"));
    }

    // -------------------------------------------------------------------
    // removeBreakpointMatcher
    // -------------------------------------------------------------------

    @Test
    public void shouldRemoveBreakpointMatcher() throws Exception {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"status\":\"removed\",\"id\":\"test-id\"}"));

        // when
        mockServerClient.removeBreakpointMatcher("test-id");

        // then
        verify(mockHttpClient).sendRequest(httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest capturedRequest = httpRequestArgumentCaptor.getValue();
        assertThat(capturedRequest.getMethod(""), is("PUT"));
        assertThat(capturedRequest.getPath().getValue(), containsString("breakpoint/matcher/remove"));
        // verify body contains the id
        String bodyStr = capturedRequest.getBodyAsString();
        JsonNode bodyNode = OBJECT_MAPPER.readTree(bodyStr);
        assertThat(bodyNode.get("id").asText(), is("test-id"));
    }

    @Test
    public void shouldRejectBlankIdForRemoveBreakpointMatcher() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> mockServerClient.removeBreakpointMatcher(""));

        // then
        assertThat(exception.getMessage(), containsString("removeBreakpointMatcher requires a non-blank id"));
    }

    @Test
    public void shouldRejectNullIdForRemoveBreakpointMatcher() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> mockServerClient.removeBreakpointMatcher(null));

        // then
        assertThat(exception.getMessage(), containsString("removeBreakpointMatcher requires a non-blank id"));
    }

    // -------------------------------------------------------------------
    // clearBreakpointMatchers
    // -------------------------------------------------------------------

    @Test
    public void shouldClearBreakpointMatchers() {
        // given
        when(mockHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class), anyBoolean()))
            .thenReturn(response().withBody("{\"status\":\"cleared\",\"count\":3}"));

        // when
        mockServerClient.clearBreakpointMatchers();

        // then
        verify(mockHttpClient).sendRequest(httpRequestArgumentCaptor.capture(), anyLong(), any(TimeUnit.class), anyBoolean());
        HttpRequest capturedRequest = httpRequestArgumentCaptor.getValue();
        assertThat(capturedRequest.getMethod(""), is("PUT"));
        assertThat(capturedRequest.getPath().getValue(), containsString("breakpoint/matcher/clear"));
    }

    // -------------------------------------------------------------------
    // addBreakpoint validation
    // -------------------------------------------------------------------

    @Test
    public void shouldRejectNullMatcherForAddBreakpoint() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> mockServerClient.addBreakpoint(null, EnumSet.of(BreakpointPhase.REQUEST),
                req -> req, null, null));

        // then
        assertThat(exception.getMessage(), containsString("addBreakpoint requires a non-null matcher"));
    }

    @Test
    public void shouldRejectNullPhasesForAddBreakpoint() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> mockServerClient.addBreakpoint(request(), null,
                req -> req, null, null));

        // then
        assertThat(exception.getMessage(), containsString("addBreakpoint requires a non-null non-empty set of phases"));
    }

    @Test
    public void shouldRejectEmptyPhasesForAddBreakpoint() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> mockServerClient.addBreakpoint(request(), EnumSet.noneOf(BreakpointPhase.class),
                req -> req, null, null));

        // then
        assertThat(exception.getMessage(), containsString("addBreakpoint requires a non-null non-empty set of phases"));
    }

    // -------------------------------------------------------------------
    // addBreakpoint WS connection failure (unit test cannot connect to real server)
    // -------------------------------------------------------------------

    @Test
    public void shouldThrowClientExceptionWhenWebSocketConnectionFails() {
        // Use a short timeout to avoid waiting 90 seconds for the default timeout
        MockServerClient shortTimeoutClient = new MockServerClient(
            ClientConfiguration.clientConfiguration().maxFutureTimeoutInMillis(1000L),
            "localhost", 1090
        );
        // In a unit test without a running server, addBreakpoint should throw
        // a ClientException wrapping the WS connection failure.
        ClientException exception = assertThrows(ClientException.class,
            () -> shortTimeoutClient.addBreakpoint(request(), EnumSet.of(BreakpointPhase.REQUEST),
                req -> req, null, null));
        assertThat(exception.getMessage(), containsString("Unable to establish breakpoint WebSocket connection"));
    }
}
