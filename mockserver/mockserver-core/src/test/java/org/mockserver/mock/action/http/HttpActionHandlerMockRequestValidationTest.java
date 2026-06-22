package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for the opt-in OpenAPI request validation on the MOCK matching path (ADV6).
 * <p>
 * Drives {@link HttpActionHandler#processAction} with a stubbed {@code firstMatchingExpectation}
 * returning an OpenAPI-backed {@link Expectation} (its requestDefinition is an
 * {@code OpenAPIDefinition} carrying a {@code specUrlOrPayload}), then inspects the response
 * actually handed to the {@link ResponseWriter}:
 * <ul>
 *   <li>flag off (default) — the matched mock response is served regardless of conformance</li>
 *   <li>flag on, malformed request — rejected with a 400 instead of the mock response</li>
 *   <li>flag on, conformant request — the matched mock response is served unchanged</li>
 * </ul>
 * The expectation is supplied directly (bypassing the real matcher) so the test isolates the
 * validation hook from the matcher's own body strictness. The matched action is a static RESPONSE,
 * so {@code HttpActionHandler} uses a real {@code HttpResponseActionHandler} that simply returns the
 * configured response body.
 */
public class HttpActionHandlerMockRequestValidationTest {

    private static final String SPEC = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");

    private MockServerLogger mockServerLogger;
    private Scheduler scheduler;
    private HttpActionHandler actionHandler;
    private Configuration configuration;
    private HttpState mockHttpStateHandler;

    @Before
    public void setup() {
        mockServerLogger = new MockServerLogger(HttpActionHandlerMockRequestValidationTest.class);
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        when(mockHttpStateHandler.getMockServerLogger()).thenReturn(mockServerLogger);
        scheduler = new Scheduler(configuration, mockServerLogger);
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());

        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);
    }

    @After
    public void tearDown() {
        scheduler.shutdown();
    }

    private Expectation createPetsExpectation() {
        return Expectation.when(SPEC, "createPets").thenRespond(response().withStatusCode(201).withBody("created"));
    }

    private HttpResponse processAndCapture(HttpRequest request, Expectation expectation) {
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ResponseWriter responseWriter = mock(ResponseWriter.class);

        // synchronous=true so the scheduler.submit validation block and the action run on this thread
        actionHandler.processAction(request, responseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter, atLeastOnce()).writeResponse(any(HttpRequest.class), captor.capture(), anyBoolean());
        return captor.getValue();
    }

    private HttpRequest malformedCreatePets() {
        // Pet requires "id" (integer) and "name" (string); this body violates the schema
        return request("/pets")
            .withMethod("POST")
            .withHeader("content-type", "application/json")
            .withBody("{\"id\":\"not-an-integer\"}");
    }

    private HttpRequest validCreatePets() {
        return request("/pets")
            .withMethod("POST")
            .withHeader("content-type", "application/json")
            .withBody("{\"id\":1,\"name\":\"Fido\"}");
    }

    @Test
    public void shouldServeMockResponseForMalformedRequestWhenValidationDisabled() {
        // given - flag off (default)
        configuration.validateRequestsAgainstOpenApiSpec(false);

        // when - a malformed request is matched by the OpenAPI-backed mock
        HttpResponse written = processAndCapture(malformedCreatePets(), createPetsExpectation());

        // then - the mock response is served unchanged, no 400
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(201));
        assertThat(written.getBodyAsString(), is("created"));
    }

    @Test
    public void shouldRejectMalformedRequestWith400WhenValidationEnabled() {
        // given - flag on
        configuration.validateRequestsAgainstOpenApiSpec(true);

        // when
        HttpResponse written = processAndCapture(malformedCreatePets(), createPetsExpectation());

        // then - rejected with a 400 describing the violation, mock response NOT served
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(400));
        assertThat(written.getBodyAsString(), containsString("OpenAPI request validation failed"));
    }

    @Test
    public void shouldServeMockResponseForValidRequestWhenValidationEnabled() {
        // given - flag on, conformant request
        configuration.validateRequestsAgainstOpenApiSpec(true);

        // when
        HttpResponse written = processAndCapture(validCreatePets(), createPetsExpectation());

        // then - request passes validation, the mock response is served
        assertThat(written, is(notNullValue()));
        assertThat(written.getStatusCode(), is(201));
        assertThat(written.getBodyAsString(), is("created"));
    }
}
