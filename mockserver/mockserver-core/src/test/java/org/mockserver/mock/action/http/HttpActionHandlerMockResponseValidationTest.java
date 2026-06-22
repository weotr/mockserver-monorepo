package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockito.ArgumentCaptor;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.OpenAPIDefinition.openAPI;

/**
 * Behavioural tests for OpenAPI response validation on the MOCK response path (C2 enforce).
 * <p>
 * Drives {@link HttpActionHandler#writeResponseActionResponse} directly with an
 * {@link OpenAPIDefinition} requestDefinition and a deliberately non-conformant response, then
 * inspects the response actually handed to the {@link ResponseWriter}:
 * <ul>
 *   <li>validation off — original (invalid) response is returned regardless of conformance</li>
 *   <li>validation on, enforce off (default) — original response returned, violation logged only</li>
 *   <li>validation on, enforce on — invalid response replaced with a 502</li>
 *   <li>validation on, enforce on, conformant response — original response returned unchanged</li>
 * </ul>
 */
public class HttpActionHandlerMockResponseValidationTest {

    private static final String SPEC = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");

    private MockServerLogger mockServerLogger;
    private Scheduler scheduler;
    private HttpActionHandler actionHandler;
    private Configuration configuration;
    private final HttpRequest request = request("/pets").withMethod("GET");

    @Before
    public void setup() {
        mockServerLogger = new MockServerLogger(HttpActionHandlerMockResponseValidationTest.class);
        configuration = configuration().logLevel(Level.INFO);
        HttpState httpState = mock(HttpState.class);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        scheduler = new Scheduler(configuration, mockServerLogger);
        when(httpState.getScheduler()).thenReturn(scheduler);
        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
    }

    @After
    public void tearDown() {
        scheduler.shutdown();
    }

    private HttpResponse writeAndCapture(HttpResponse response, OpenAPIDefinition requestDefinition) {
        ResponseWriter responseWriter = mock(ResponseWriter.class);

        // synchronous=true so the write runs on this thread and skips the breakpoint path.
        // The response itself doubles as the Action argument (HttpResponse extends Action); its
        // type is only consulted by the breakpoint path, which synchronous=true bypasses.
        actionHandler.writeResponseActionResponse(
            response,
            responseWriter,
            request,
            response,
            true,
            requestDefinition
        );

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter).writeResponse(any(HttpRequest.class), captor.capture(), anyBoolean());
        return captor.getValue();
    }

    private HttpResponse nonConformantResponse() {
        // listPets expects a JSON array; an object is non-conformant
        return response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");
    }

    private HttpResponse conformantResponse() {
        return response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");
    }

    @Test
    public void shouldReturnInvalidResponseUnchangedWhenValidationDisabled() {
        // given - validation off (default)
        configuration.openAPIResponseValidation(false);
        configuration.enforceResponseValidationForMocks(true); // even with enforce on, no effect when validation off
        HttpResponse invalid = nonConformantResponse();

        // when
        HttpResponse written = writeAndCapture(invalid, openAPI(SPEC, "listPets"));

        // then - original returned, no 502
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(200));
        assertThat(written, is(sameInstance(invalid)));
    }

    @Test
    public void shouldReturnInvalidResponseUnchangedWhenValidationOnButEnforceOff() {
        // given - validation on, enforce off (default behaviour - advisory only)
        configuration.openAPIResponseValidation(true);
        configuration.enforceResponseValidationForMocks(false);
        HttpResponse invalid = nonConformantResponse();

        // when
        HttpResponse written = writeAndCapture(invalid, openAPI(SPEC, "listPets"));

        // then - original (invalid) response still returned to the client
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(200));
        assertThat(written, is(sameInstance(invalid)));
    }

    @Test
    public void shouldReplaceInvalidResponseWith502WhenEnforceOn() {
        // given - validation on, enforce on
        configuration.openAPIResponseValidation(true);
        configuration.enforceResponseValidationForMocks(true);
        HttpResponse invalid = nonConformantResponse();

        // when
        HttpResponse written = writeAndCapture(invalid, openAPI(SPEC, "listPets"));

        // then - replaced with a 502 describing the violation
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(502));
        assertThat(written.getBodyAsString(), containsString("OpenAPI response validation failed"));
    }

    @Test
    public void shouldReturnConformantResponseUnchangedWhenEnforceOn() {
        // given - validation on, enforce on, but the response conforms
        configuration.openAPIResponseValidation(true);
        configuration.enforceResponseValidationForMocks(true);
        HttpResponse valid = conformantResponse();

        // when
        HttpResponse written = writeAndCapture(valid, openAPI(SPEC, "listPets"));

        // then - original response returned unchanged (no 502)
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(200));
        assertThat(written, is(sameInstance(valid)));
    }
}
