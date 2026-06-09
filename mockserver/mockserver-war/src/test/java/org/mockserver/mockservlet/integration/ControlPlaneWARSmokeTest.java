package org.mockserver.mockservlet.integration;

import org.apache.catalina.Context;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mockservlet.MockServerServlet;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.mockserver.testing.integration.mock.AbstractMockingIntegrationTestBase;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.NOT_FOUND_404;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.validator.jsonschema.JsonSchemaValidator.OPEN_API_SPECIFICATION_URL;

/**
 * WAR-transport control-plane decode smoke test.
 * <p>
 * After the control-plane decomposition (step 1), the 28 transport-agnostic
 * control-plane methods run only against Netty HTTP/1.1.  This small smoke
 * ensures the WAR servlet decode path ({@code HttpServletRequestToMockServerHttpRequestDecoder}
 * / {@code BodyServletDecoderEncoder}) is still exercised for control-plane
 * JSON-body requests.
 * <p>
 * Three representative scenarios are chosen:
 * <ol>
 *   <li>{@link #shouldClearExpectationsAndLogs} &mdash; PUT JSON to /clear</li>
 *   <li>{@link #shouldReset} &mdash; PUT to /reset</li>
 *   <li>{@link #shouldErrorForInvalidExpectation} &mdash; PUT invalid JSON to /expectation</li>
 * </ol>
 * Scenarios are identical to those in
 * {@link org.mockserver.testing.integration.mock.AbstractControlPlaneIntegrationTest}.
 *
 * @see org.mockserver.testing.integration.mock.AbstractControlPlaneIntegrationTest
 */
public class ControlPlaneWARSmokeTest extends AbstractMockingIntegrationTestBase {

    private static final int SERVER_HTTP_PORT = PortFactory.findFreePort();
    private static final int SERVER_HTTPS_PORT = PortFactory.findFreePort();
    private static Tomcat tomcat;

    @BeforeClass
    @SuppressWarnings("deprecation")
    public static void startServer() throws Exception {
        servletContext = "";

        tomcat = new Tomcat();
        tomcat.setBaseDir(new File(".").getCanonicalPath() + File.separatorChar + "tomcat_control_plane_smoke");

        // add http port
        tomcat.setPort(SERVER_HTTP_PORT);
        Connector defaultConnector = tomcat.getConnector();
        defaultConnector.setRedirectPort(SERVER_HTTPS_PORT);

        // add https connector
        KeyStoreFactory keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());
        keyStoreFactory.loadOrCreateKeyStore();
        Connector httpsConnector = new Connector();
        httpsConnector.setPort(SERVER_HTTPS_PORT);
        httpsConnector.setSecure(true);
        httpsConnector.setScheme("https");
        httpsConnector.setProperty("SSLEnabled", "true");
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setSslProtocol("TLS");
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateKeyAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS);
        certificate.setCertificateKeystoreFile(new File(keyStoreFactory.keyStoreFileName).getAbsolutePath());
        certificate.setCertificateKeystorePassword(KeyStoreFactory.KEY_STORE_PASSWORD);
        sslHostConfig.addCertificate(certificate);
        httpsConnector.addSslHostConfig(sslHostConfig);

        Service service = tomcat.getService();
        service.addConnector(httpsConnector);

        // add servlet
        Context ctx = tomcat.addContext("/" + servletContext, new File(".").getAbsolutePath());
        tomcat.addServlet("/" + servletContext, "mockServerServlet", new MockServerServlet());
        ctx.addServletMappingDecoded("/*", "mockServerServlet");
        ctx.addApplicationListener(MockServerServlet.class.getName());

        // start server
        tomcat.start();

        // start client
        mockServerClient = new MockServerClient("localhost", SERVER_HTTP_PORT, servletContext);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        // stop client
        stopQuietly(mockServerClient);

        // stop mock server
        if (tomcat != null) {
            tomcat.stop();
            tomcat.getServer().await();
        }

        // wait for server to shutdown
        TimeUnit.MILLISECONDS.sleep(500);
    }

    @Override
    public int getServerPort() {
        return SERVER_HTTP_PORT;
    }

    @Override
    public int getServerSecurePort() {
        return SERVER_HTTPS_PORT;
    }

    // ========================================================================
    // Control-plane decode scenarios (from AbstractControlPlaneIntegrationTest)
    // ========================================================================

    /**
     * Exercises: mockServerClient.clear(request) which PUTs a JSON-serialised
     * request matcher to /mockserver/clear via the servlet decode path.
     */
    @Test
    public void shouldClearExpectationsAndLogs() {
        // given - some expectations
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path1"))
            )
            .respond(
                response()
                    .withBody("some_body1")
            );
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path2"))
            )
            .respond(
                response()
                    .withBody("some_body2")
            );

        // and - some matching requests
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body1"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body2"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path2")),
                getHeadersToRemove()
            )
        );

        // when
        mockServerClient
            .clear(
                request()
                    .withPath(calculatePath("some_path1"))
            );

        // then - expectations cleared
        assertThat(
            mockServerClient.retrieveActiveExpectations(null),
            arrayContaining(
                new Expectation(request()
                    .withPath(calculatePath("some_path2")))
                    .thenRespond(
                        response()
                            .withBody("some_body2")
                    )
            )
        );

        // and then - request log cleared
        verifyRequestsMatches(
            mockServerClient.retrieveRecordedRequests(null),
            request(calculatePath("some_path2"))
        );

        // and then - remaining expectations not cleared
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body2"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path2")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
    }

    /**
     * Exercises: mockServerClient.reset() which PUTs to /mockserver/reset
     * via the servlet decode path.
     */
    @Test
    public void shouldReset() {
        // given
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path1"))
            )
            .respond(
                response()
                    .withBody("some_body1")
            );
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path2"))
            )
            .respond(
                response()
                    .withBody("some_body2")
            );

        // when
        mockServerClient.reset();

        // then
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path2")),
                getHeadersToRemove()
            )
        );
    }

    /**
     * Exercises: raw PUT of an invalid JSON body to /mockserver/expectation
     * via the servlet decode path.  This is the strongest JSON-body decode
     * test because the body goes through {@code BodyServletDecoderEncoder}
     * before reaching the expectation validator.
     */
    @Test
    public void shouldErrorForInvalidExpectation() throws Exception {
        // when
        HttpResponse httpResponse = makeRequest(
            request()
                .withMethod("PUT")
                .withHeader(HOST.toString(), "localhost:" + this.getServerPort())
                .withPath(addContextToPath("expectation"))
                .withBody("{" + NEW_LINE +
                    "  \"httpRequest\" : {" + NEW_LINE +
                    "    \"path\" : \"/path_one\"" + NEW_LINE +
                    "  }," + NEW_LINE +
                    "  \"incorrectField\" : {" + NEW_LINE +
                    "    \"body\" : \"some_body_one\"" + NEW_LINE +
                    "  }," + NEW_LINE +
                    "  \"times\" : {" + NEW_LINE +
                    "    \"remainingTimes\" : 1" + NEW_LINE +
                    "  }," + NEW_LINE +
                    "  \"timeToLive\" : {" + NEW_LINE +
                    "    \"unlimited\" : true" + NEW_LINE +
                    "  }" + NEW_LINE +
                    "}"),
            getHeadersToRemove()
        );

        // then
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), is("incorrect expectation json format for:" + NEW_LINE +
            "" + NEW_LINE +
            "  {" + NEW_LINE +
            "    \"httpRequest\" : {" + NEW_LINE +
            "      \"path\" : \"/path_one\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"incorrectField\" : {" + NEW_LINE +
            "      \"body\" : \"some_body_one\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"times\" : {" + NEW_LINE +
            "      \"remainingTimes\" : 1" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"timeToLive\" : {" + NEW_LINE +
            "      \"unlimited\" : true" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }" + NEW_LINE +
            "" + NEW_LINE +
            " schema validation errors:" + NEW_LINE +
            "" + NEW_LINE +
            "  23 errors:" + NEW_LINE +
            "   - $.binaryResponse: is missing but it is required" + NEW_LINE +
            "   - $.dnsResponse: is missing but it is required" + NEW_LINE +
            "   - $.grpcBidiResponse: is missing, but is required, if specifying action of type GrpcBidiResponse" + NEW_LINE +
            "   - $.grpcStreamResponse: is missing, but is required, if specifying action of type GrpcStreamResponse" + NEW_LINE +
            "   - $.httpError: is missing, but is required, if specifying action of type Error" + NEW_LINE +
            "   - $.httpForward: is missing, but is required, if specifying action of type Forward" + NEW_LINE +
            "   - $.httpForwardClassCallback: is missing, but is required, if specifying action of type ForwardClassCallback" + NEW_LINE +
            "   - $.httpForwardObjectCallback: is missing, but is required, if specifying action of type ForwardObjectCallback" + NEW_LINE +
            "   - $.httpForwardTemplate: is missing, but is required, if specifying action of type ForwardTemplate" + NEW_LINE +
            "   - $.httpForwardValidateAction: is missing, but is required, if specifying action of type ForwardValidateAction" + NEW_LINE +
            "   - $.httpForwardWithFallback: is missing, but is required, if specifying action of type ForwardWithFallback" + NEW_LINE +
            "   - $.httpLlmResponse: is missing, but is required, if specifying action of type LlmResponse" + NEW_LINE +
            "   - $.httpOverrideForwardedRequest: is missing, but is required, if specifying action of type OverrideForwardedRequest" + NEW_LINE +
            "   - $.httpResponse: is missing, but is required, if specifying action of type Response" + NEW_LINE +
            "   - $.httpResponseClassCallback: is missing, but is required, if specifying action of type ResponseClassCallback" + NEW_LINE +
            "   - $.httpResponseObjectCallback: is missing, but is required, if specifying action of type ResponseObjectCallback" + NEW_LINE +
            "   - $.httpResponseTemplate: is missing, but is required, if specifying action of type ResponseTemplate" + NEW_LINE +
            "   - $.httpResponses: is missing, but is required, if specifying action of type Responses" + NEW_LINE +
            "   - $.httpSseResponse: is missing, but is required, if specifying action of type SseResponse" + NEW_LINE +
            "   - $.httpWebSocketResponse: is missing, but is required, if specifying action of type WebSocketResponse" + NEW_LINE +
            "   - $.incorrectField: is not defined in the schema and the schema does not allow additional properties" + NEW_LINE +
            "   - $.steps: is missing but it is required" + NEW_LINE +
            "   - oneOf of the following must be specified [httpError, httpForward, httpForwardClassCallback, httpForwardObjectCallback, httpForwardTemplate, httpForwardValidateAction, httpForwardWithFallback, httpOverrideForwardedRequest, httpResponse, httpResponseClassCallback, httpResponseObjectCallback, httpResponseTemplate]" + NEW_LINE +
            "  " + NEW_LINE +
            "  " + OPEN_API_SPECIFICATION_URL.replaceAll(NEW_LINE, NEW_LINE + "  ")));
    }

    private static void assertEquals(HttpResponse expected, HttpResponse actual) {
        assertThat(actual, is(expected));
    }
}
