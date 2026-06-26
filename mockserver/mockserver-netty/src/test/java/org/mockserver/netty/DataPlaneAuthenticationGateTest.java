package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Transport-agnostic unit tests for the shared {@link DataPlaneAuthenticationGate}. These need no QUIC
 * and no running server — they drive the gate directly with a capturing {@link ResponseWriter}, which is
 * exactly what both {@code HttpRequestHandler} and {@code Http3MockServerHandler} invoke. This guarantees
 * the control-plane exemption and the 401 path hold on EVERY transport even when the QUIC e2e is skipped.
 */
public class DataPlaneAuthenticationGateTest {

    private final MockServerLogger logger = new MockServerLogger();

    /** Captures the last response written; sendResponse is the single sink both real writers implement. */
    private static final class CapturingResponseWriter extends ResponseWriter {
        HttpResponse captured;

        CapturingResponseWriter(Configuration configuration, MockServerLogger logger) {
            super(configuration, logger);
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.captured = response;
        }
    }

    private CapturingResponseWriter writer(Configuration configuration) {
        return new CapturingResponseWriter(configuration, logger);
    }

    @Test
    public void defaultOffProceedsWithoutWritingResponse() {
        Configuration configuration = configuration();
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, request().withPath("/anything"), writer);

        assertThat(authenticated, is(true));
        assertThat("default-off must not write any response", writer.captured, is(nullValue()));
    }

    @Test
    public void enabledDataPlaneRequestWithNoCredentialsIsRejectedWith401() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, request().withPath("/mocked"), writer);

        assertThat(authenticated, is(false));
        assertThat(writer.captured, is(org.hamcrest.Matchers.notNullValue()));
        assertThat(writer.captured.getStatusCode(), is(401));
        assertThat(writer.captured.getBodyAsString(), is("Unauthorized for data plane"));
    }

    @Test
    public void enabledDataPlaneRequestWithValidBearerProceeds() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, request().withPath("/mocked").withHeader("Authorization", "Bearer tok"), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    @Test
    public void basicChallengeIncludesConfiguredRealmOn401() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBasicAuthenticationRealm("my-realm");
        CapturingResponseWriter writer = writer(configuration);

        DataPlaneAuthenticationGate.isAuthenticated(configuration, logger, request().withPath("/mocked"), writer);

        assertThat(writer.captured.getStatusCode(), is(401));
        assertThat(writer.captured.getFirstHeader("WWW-Authenticate"), containsString("Basic realm=\"my-realm\""));
    }

    @Test
    public void validBasicCredentialsProceed() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret");
        CapturingResponseWriter writer = writer(configuration);
        String header = "Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, request().withPath("/mocked").withHeader("Authorization", header), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    // ---- control-plane / probe exemption (this is the HTTP/3 regression the e2e caught) ----

    @Test
    public void controlPlaneRequestIsNeverGatedEvenWithNoCredentials() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        // /mockserver/status, /mockserver/expectation etc. must pass WITHOUT data-plane credentials so
        // the operator can administer a locked-down server (and health probes keep working).
        for (String controlPlanePath : new String[]{
            "/mockserver/status", "/mockserver/ready", "/mockserver/expectation",
            "/mockserver/bind", "/mockserver/stop", "/mockserver/dashboard"}) {
            boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
                configuration, logger, request().withPath(controlPlanePath), writer);
            assertThat("control-plane path must be exempt: " + controlPlanePath, authenticated, is(true));
            assertThat("control-plane path must not be 401'd: " + controlPlanePath, writer.captured, is(nullValue()));
        }
    }

    @Test
    public void livenessProbePathIsExemptWhenConfigured() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok")
            .livenessHttpGetPath("/liveness/probe");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, request().withPath("/liveness/probe"), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    @Test
    public void nonControlPlaneDataPlanePathIsStillGated() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok")
            .livenessHttpGetPath("/liveness/probe");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, request().withPath("/some/mocked/api"), writer);

        assertThat(authenticated, is(false));
        assertThat(writer.captured.getStatusCode(), is(401));
    }
}
