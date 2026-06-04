package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.testing.integration.mock.AbstractBasicMockingSameJVMIntegrationTest;

import static org.mockserver.configuration.ConfigurationProperties.assumeAllRequestsAreHttp;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class CustomNonStandardHTTPMethodsIntegrationTest extends AbstractBasicMockingSameJVMIntegrationTest {

    private static boolean assumeAllRequestsAreHttp;

    @BeforeClass
    public static void startServer() {
        // save original value
        assumeAllRequestsAreHttp = assumeAllRequestsAreHttp();

        // allow custom http methods
        assumeAllRequestsAreHttp(true);

        mockServerClient = ClientAndServer.startClientAndServer();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);

        // set back to original value
        assumeAllRequestsAreHttp(assumeAllRequestsAreHttp);
    }

    @Override
    public int getServerPort() {
        return mockServerClient.getPort();
    }

    @Test
    public void shouldReturnResponseByMatchingCustomHTTPMethod() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("PURGE")
            )
            .respond(
                response()
                    .withBody("some_body_response")
            );

        // then
        assertThat(makeRequest(
                request()
                    .withMethod("PURGE"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_response")));
        assertThat(makeRequest(
                request(),
                getHeadersToRemove()
            ), is(localNotFoundResponse()));
    }
}
