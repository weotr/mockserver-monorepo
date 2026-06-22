package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.audit.AuditStore;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * End-to-end routing tests for {@code GET /mockserver/audit} exercised through
 * {@link HttpState#handle}: returns the audit log newest-first as a JSON array,
 * respects {@code ?limit}, and is auth-gated (401 when the handler rejects).
 *
 * <p>State-mutating: drives the process-wide {@link AuditStore} singleton, so it
 * must run in the sequential Surefire phase.
 */
public class HttpStateAuditEndpointTest {

    private HttpState httpState;
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static class FakeResponseWriter extends ResponseWriter {
        public HttpResponse response;

        protected FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @Before
    public void setUp() {
        AuditStore.getInstance().clear();
        // audit enabled + reads enabled so the GET endpoint itself does not pollute assertions
        // is irrelevant here: we seed entries directly and read them back.
        Configuration configuration = configuration().controlPlaneAuditEnabled(true);
        Scheduler scheduler = new Scheduler(configuration, new MockServerLogger(configuration, HttpStateAuditEndpointTest.class), true);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, HttpStateAuditEndpointTest.class), scheduler);
    }

    @After
    public void tearDown() {
        AuditStore.getInstance().clear();
    }

    private void seedMutation(String operation) {
        seedMutation(operation, "{}");
    }

    private void seedMutation(String operation, String body) {
        FakeResponseWriter rw = new FakeResponseWriter();
        httpState.handle(request("/mockserver/" + operation).withMethod("PUT").withBody(body), rw, false);
    }

    private HttpResponse get(String query) {
        FakeResponseWriter rw = new FakeResponseWriter();
        HttpRequest req = request("/mockserver/audit").withMethod("GET");
        if (query != null) {
            req.withQueryStringParameter("limit", query);
        }
        assertThat("route handled", httpState.handle(req, rw, false), is(true));
        return rw.response;
    }

    @Test
    public void returnsEntriesNewestFirst() throws Exception {
        // use mutations that do not themselves clear the audit store (PUT /reset would)
        seedMutation("clear");
        seedMutation("mode");
        HttpResponse response = get(null);
        assertThat(response.getStatusCode(), is(200));
        JsonNode array = objectMapper.readTree(response.getBodyAsString());
        assertThat(array.isArray(), is(true));
        assertThat(array.size(), is(2));
        // newest first
        assertThat(array.get(0).get("operation").asText(), is("mode"));
        assertThat(array.get(1).get("operation").asText(), is("clear"));
    }

    @Test
    public void respectsLimit() throws Exception {
        seedMutation("clear");
        seedMutation("mode");
        seedMutation("expectation", "[{\"httpRequest\":{\"path\":\"/x\"},\"httpResponse\":{\"statusCode\":200}}]");
        HttpResponse response = get("1");
        assertThat(response.getStatusCode(), is(200));
        JsonNode array = objectMapper.readTree(response.getBodyAsString());
        assertThat(array.size(), is(1));
        assertThat(array.get(0).get("operation").asText(), is("expectation"));
    }

    @Test
    public void isAuthGated() {
        AuthenticationHandler rejectingHandler = req -> false;
        httpState.setControlPlaneAuthenticationHandler(rejectingHandler);
        HttpResponse response = get(null);
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("Unauthorized for control plane"));
    }

    @Test
    public void clientSafeAuthExceptionDetailEchoedToClient() {
        // legacy JWT / mTLS path: the detailed reason has always been surfaced to the client (unchanged)
        AuthenticationHandler handler = req -> {
            throw new org.mockserver.authentication.AuthenticationException("Expired JWT");
        };
        httpState.setControlPlaneAuthenticationHandler(handler);
        HttpResponse response = get(null);
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), is("Unauthorized for control plane - Expired JWT"));
    }

    @Test
    public void oidcClientUnsafeAuthExceptionReturnsGenericBody() {
        // OIDC path: detail (expected issuer/audience/scopes) is logged server-side only;
        // the client receives a generic body that does NOT disclose the reason
        AuthenticationHandler handler = req -> {
            throw new org.mockserver.authentication.AuthenticationException(
                "JWT iss claim has value https://evil.example.com, must be https://idp.example.com", false);
        };
        httpState.setControlPlaneAuthenticationHandler(handler);
        HttpResponse response = get(null);
        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), is("Unauthorized for control plane"));
        assertThat(response.getBodyAsString(), not(containsString("evil.example.com")));
        assertThat(response.getBodyAsString(), not(containsString("idp.example.com")));
    }
}
