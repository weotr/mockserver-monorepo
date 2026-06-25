package org.mockserver.mock.action.http;

import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;

/**
 * Tests for the attachMismatchDiagnosticToResponse feature.
 * Verifies that:
 * (a) default OFF: unmatched response is unchanged (no diagnostic);
 * (b) ON: diagnostic header and body are present.
 */
public class HttpActionHandlerMismatchDiagnosticTest {

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
    @Mock
    private HttpResponseTemplateActionHandler mockHttpResponseTemplateActionHandler;
    @Mock
    private HttpResponseClassCallbackActionHandler mockHttpResponseClassCallbackActionHandler;
    @Mock
    private HttpResponseObjectCallbackActionHandler mockHttpResponseObjectCallbackActionHandler;
    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @Mock
    private HttpForwardTemplateActionHandler mockHttpForwardTemplateActionHandler;
    @Mock
    private HttpForwardClassCallbackActionHandler mockHttpForwardClassCallbackActionHandler;
    @Mock
    private HttpForwardObjectCallbackActionHandler mockHttpForwardObjectCallbackActionHandler;
    @Mock
    private HttpOverrideForwardedRequestActionHandler mockHttpOverrideForwardedRequestActionHandler;
    @Mock
    private HttpErrorActionHandler mockHttpErrorActionHandler;
    @Mock
    private ResponseWriter mockResponseWriter;
    @Mock
    private MockServerLogger mockServerLogger;
    @Spy
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
    @Mock
    private NettyHttpClient mockNettyHttpClient;
    private HttpState mockHttpStateHandler;
    @InjectMocks
    private HttpActionHandler actionHandler;
    private Configuration configuration;

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @Test
    public void defaultOff_unmatchedResponseHasNoDiagnostic() {
        // given - default config (attachMismatchDiagnosticToResponse = false)
        HttpRequest request = request("some_path");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - response is a plain 404 with no diagnostic header or body
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(404));
        assertThat(writtenResponse.getFirstHeader("x-mockserver-closest-match"), is(emptyOrNullString()));
        assertThat(writtenResponse.getBodyAsString(), is(emptyOrNullString()));
    }

    @Test
    public void whenEnabled_unmatchedResponseContainsDiagnosticHeaderAndBody() {
        // given - enable diagnostic
        configuration.attachMismatchDiagnosticToResponse(true);
        HttpRequest request = request("some_path");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);

        // set up closest match diff to simulate a method mismatch
        Map<MatchDifference.Field, List<String>> diff = new LinkedHashMap<>();
        diff.put(MatchDifference.Field.METHOD, List.of("expected POST but was GET"));
        when(mockHttpStateHandler.findClosestMatchHint(request)).thenReturn(new RequestMatchers.ClosestMatchHint(null, diff));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - response has diagnostic header
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(404));
        assertThat(writtenResponse.getFirstHeader("x-mockserver-closest-match"), containsString("method"));

        // then - response body contains JSON diagnostic
        String body = writtenResponse.getBodyAsString();
        assertThat(body, containsString("\"differences\""));
        assertThat(body, containsString("\"method\""));
        assertThat(body, containsString("expected POST but was GET"));
        assertThat(body, containsString("\"matchedFieldCount\""));
        assertThat(body, containsString("\"totalFieldCount\""));
    }

    @Test
    public void whenEnabled_noExpectationsConfigured_headerIndicatesNoExpectations() {
        // given - enable diagnostic, but no expectations at all (diff returns null)
        configuration.attachMismatchDiagnosticToResponse(true);
        HttpRequest request = request("some_path");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);
        when(mockHttpStateHandler.findClosestMatchHint(request)).thenReturn(null);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(404));
        assertThat(writtenResponse.getFirstHeader("x-mockserver-closest-match"), is("no expectations configured"));
        // body should remain empty when no expectations present
        assertThat(writtenResponse.getBodyAsString(), is(emptyOrNullString()));
    }

    @Test
    public void whenEnabled_multipleFieldDifferences_allFieldsInHeader() {
        // given
        configuration.attachMismatchDiagnosticToResponse(true);
        HttpRequest request = request("some_path");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);

        Map<MatchDifference.Field, List<String>> diff = new LinkedHashMap<>();
        diff.put(MatchDifference.Field.METHOD, List.of("expected POST but was GET"));
        diff.put(MatchDifference.Field.PATH, List.of("expected /api/users but was /some_path"));
        diff.put(MatchDifference.Field.HEADERS, List.of("missing header Content-Type"));
        when(mockHttpStateHandler.findClosestMatchHint(request)).thenReturn(new RequestMatchers.ClosestMatchHint(null, diff));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - header lists all differing fields
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        String header = writtenResponse.getFirstHeader("x-mockserver-closest-match");
        assertThat(header, containsString("method"));
        assertThat(header, containsString("path"));
        assertThat(header, containsString("headers"));
    }

    @Test
    public void whenDisabled_explicitlyFalse_noDiagnostic() {
        // given - both diagnostics explicitly disabled (the compact hint defaults ON, so disable it too
        // to isolate the verbose-diagnostic behaviour under test)
        configuration.attachMismatchDiagnosticToResponse(false);
        configuration.closestMatchHintEnabled(false);
        HttpRequest request = request("some_path");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);

        Map<MatchDifference.Field, List<String>> diff = new LinkedHashMap<>();
        diff.put(MatchDifference.Field.METHOD, List.of("expected POST but was GET"));
        lenient().when(mockHttpStateHandler.findClosestMatchHint(request)).thenReturn(new RequestMatchers.ClosestMatchHint(null, diff));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - no verbose diagnostic header or body even though a diff is available
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(404));
        assertThat(writtenResponse.getFirstHeader("x-mockserver-closest-match"), is(emptyOrNullString()));
        assertThat(writtenResponse.getBodyAsString(), is(emptyOrNullString()));
    }
}
