package org.mockserver.testing.integration.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.verify.VerificationTimes;

import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.matchers.Times.*;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.*;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.model.OpenAPIDefinition.openAPI;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.exact;

/**
 * Focused packaging-smoke base for maven-invoker subprojects.
 * <p>
 * Contains one representative test for each major code path so that any
 * packaging/shading break is caught, without re-running the full 61-method
 * matching suite (which is already fully covered by the in-reactor
 * {@link AbstractBasicMockingIntegrationTest} hierarchy).
 * <p>
 * Every method here is copied verbatim from AbstractBasicMockingIntegrationTest.
 *
 * @author jamesdbloom
 */
public abstract class AbstractPackagingSmokeTest extends AbstractMockingIntegrationTestBase {

    protected HttpResponse localNotFoundResponse() {
        return response()
            .withStatusCode(NOT_FOUND_404.code())
            .withReasonPhrase(NOT_FOUND_404.reasonPhrase());
    }

    protected abstract boolean supportsHTTP2();

    // ---- 1. Simple response mock + match ----

    @Test
    public void shouldReturnResponseWithOnlyBody() {
        // when
        Expectation[] upsertedExpectations = mockServerClient.when(request()).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
        assertThat(upsertedExpectations.length, is(1));
        assertThat(upsertedExpectations[0], is(new Expectation(request()).thenRespond(response().withBody("some_body"))));
    }

    // ---- 2. Path + method match ----

    @Test
    public void shouldReturnResponseByMatchingPathAndMethod() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    // ---- 3. Query parameter match (via schema header/cookie/parameter) ----

    @Test
    public void shouldReturnResponseByMatchingSchemaHeaderCookieAndParameter() {
        // when
        mockServerClient
            .when(
                request()
                    .withHeader(Header.schemaHeader(
                        "headerName", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^headerVal[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
                    .withQueryStringParameter(Parameter.schemaParam(
                        "parameterName", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^parameterVal[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
                    .withCookie(Cookie.schemaCookie(
                        "cookieName", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^cookieVal[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path?parameterName=parameterValue"))
                    .withHeader("headerName", "headerValue")
                    .withCookie("cookieName", "cookieValue")
                ,
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path?parameterName=parameterOtherValue"))
                    .withHeader("headerName", "headerValue")
                    .withCookie("cookieName", "cookieValue")
                ,
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path?parameterName=parameterValue"))
                    .withHeader("headerName", "headerOtherValue")
                    .withCookie("cookieName", "cookieValue")
                ,
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path?parameterName=parameterValue"))
                    .withHeader("headerName", "headerValue")
                    .withCookie("cookieName", "cookieOtherValue")
                ,
                getHeadersToRemove()
            )
        );
    }

    // ---- 4. String body match ----

    @Test
    public void shouldReturnResponseByMatchingStringBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(exact("some_random_body"))
            )
            .respond(
                response()
                    .withBody("some_string_body_response")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_string_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody("some_random_body"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    // ---- 5. JSON body match (via OpenAPI spec) ----

    @Test
    public void shouldReturnResponseByMatchingOpenAPISpecWithOperationId() throws JsonProcessingException {
        // when
        Expectation[] upsertedExpectations = mockServerClient
            .when(openAPI(
                FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json"),
                "listPets"
            ))
            .respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath("/v1/pets")
                    .withQueryStringParameter("limit", "10"),
                getHeadersToRemove()
            )
        );
        assertThat(upsertedExpectations.length, is(1));
        assertThat(upsertedExpectations[0], is(new Expectation(openAPI(
            ObjectMapperFactory.createObjectMapper().readTree(FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json")).toPrettyString(),
            "listPets"
        )).thenRespond(response().withBody("some_body"))));
    }

    // ---- 6. Not/regex body match ----

    @Test
    public void shouldReturnResponseByMatchingNotBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path"))
                    .withBody(Not.not(regex(".+")))
            )
            .respond(
                response()
                    .withBody("some_response_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_response_body"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody("some_random_body"),
                getHeadersToRemove()
            )
        );
    }

    // ---- 7. Response template (Velocity) ----

    @Test
    public void shouldReturnResponseFromVelocityTemplate() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                template(
                    HttpTemplate.TemplateType.VELOCITY,
                    "{" + NEW_LINE +
                        "     \"statusCode\": 200," + NEW_LINE +
                        "     \"headers\": [ { \"name\": \"name\", \"values\": [ \"$!request.headers['name'][0]\" ] } ]," + NEW_LINE +
                        "     \"body\": \"$!request.body\"" + NEW_LINE +
                        "}" + NEW_LINE
                )
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader("name", "value")
                .withBody("some_request_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withHeader("name", "value")
                    .withBody("some_request_body"),
                getHeadersToRemove()
            )
        );
    }

    // ---- 7b. Response template (Mustache — distinct shaded engine from Velocity) ----

    @Test
    public void shouldReturnResponseFromMustacheTemplate() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                template(
                    HttpTemplate.TemplateType.MUSTACHE,
                    "{" + NEW_LINE +
                        "     \"statusCode\": 200," + NEW_LINE +
                        "     \"headers\": [ { \"name\": \"name\", \"values\": [ \"{{ request.headers.name.0 }}\" ] } ]," + NEW_LINE +
                        "     \"body\": \"{{ request.body }}\"" + NEW_LINE +
                        "}" + NEW_LINE
                )
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader("name", "value")
                .withBody("some_request_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withHeader("name", "value")
                    .withBody("some_request_body"),
                getHeadersToRemove()
            )
        );
    }

    // ---- 8. Forward (httpForward) ----

    @Test
    public void shouldForwardRequestInHTTP() {
        // when
        Expectation[] upsertedExpectations = mockServerClient
            .when(
                request()
                    .withPath(calculatePath("echo"))
            )
            .forward(
                forward()
                    .withHost("127.0.0.1")
                    .withPort(insecureEchoServer.getPort())
            );

        // then
        assertThat(upsertedExpectations.length, is(1));
        assertThat(upsertedExpectations[0], is(
            new Expectation(
                request()
                    .withPath(calculatePath("echo"))
            )
                .thenForward(
                    forward()
                        .withHost("127.0.0.1")
                        .withPort(insecureEchoServer.getPort())
                )
        ));
        // - in http
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-test", "test_headers_and_body")
                )
                .withBody("an_example_body_http"),
            makeRequest(
                request()
                    .withPath(calculatePath("echo"))
                    .withMethod("POST")
                    .withHeaders(
                        header("x-test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            )
        );
        // - in https
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-test", "test_headers_and_body")
                )
                .withBody("an_example_body_https"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("echo"))
                    .withMethod("POST")
                    .withHeaders(
                        header("x-test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_https"),
                getHeadersToRemove()
            )
        );
    }

    // ---- 9. Verification (verify received request) + HTTPS ----

    @Test
    public void shouldVerifyReceivedRequestsSpecificTimesInHttpAndHttps() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")), exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // and
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );

        // then
        mockServerClient.verify(request().withPath(calculatePath("some_path")));
        mockServerClient.verify(request().withPath(calculatePath("some_path")), VerificationTimes.exactly(1));

        // when
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );

        // then
        mockServerClient.verify(request().withPath(calculatePath("some_path")));
        mockServerClient.verify(request().withPath(calculatePath("some_path")), VerificationTimes.atLeast(1));
        mockServerClient.verify(request().withPath(calculatePath("some_path")), VerificationTimes.exactly(2));
        mockServerClient.verify(request().withPath(calculatePath("some_path")).withSecure(true), VerificationTimes.exactly(1));
        mockServerClient.verify(request().withPath(calculatePath("some_path")).withSecure(false), VerificationTimes.exactly(1));
    }

    // ---- 10. Clear ----

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
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
    }

    // ---- 11. Reset ----

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
        // - in http
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path2")),
                getHeadersToRemove()
            )
        );
        // - in https
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path2")),
                getHeadersToRemove()
            )
        );
    }

    // ---- 12. HTTPS (secure) request ----

    @Test
    public void shouldReturnResponseInHTTPS() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("some_body_response")
            );

        // then
        // - in https
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );
    }

    // ---- 13. Status code only (minimal response) ----

    @Test
    public void shouldReturnResponseWithOnlyStatusCode() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );
    }

    // ---- 14. Retrieve active expectations (exercises forward action) ----

    @Test
    public void shouldRetrieveActiveExpectations() {
        // when
        HttpRequest complexRequest = request()
            .withPath(calculatePath("some_path.*"))
            .withHeader("some", "header")
            .withQueryStringParameter("some", "parameter")
            .withCookie("some", "parameter")
            .withBody("some_body");
        mockServerClient.when(complexRequest, exactly(4))
            .respond(response().withBody("some_body"));
        mockServerClient.when(request().withPath(calculatePath("some_path.*")))
            .respond(response().withBody("some_body"));
        mockServerClient.when(request().withPath(calculatePath("some_other_path")))
            .respond(response().withBody("some_other_body"));
        mockServerClient.when(request().withPath(calculatePath("some_forward_path")))
            .forward(forward());

        // then
        assertThat(
            mockServerClient.retrieveActiveExpectations(request().withPath(calculatePath("some_path.*"))),
            arrayContaining(
                new Expectation(complexRequest, exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body"))
            )
        );

        assertThat(
            mockServerClient.retrieveActiveExpectations(null),
            arrayContaining(
                new Expectation(complexRequest, exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_other_path")))
                    .thenRespond(response().withBody("some_other_body")),
                new Expectation(request().withPath(calculatePath("some_forward_path")))
                    .thenForward(forward())
            )
        );

        assertThat(
            mockServerClient.retrieveActiveExpectations(request()),
            arrayContaining(
                new Expectation(complexRequest, exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_other_path")))
                    .thenRespond(response().withBody("some_other_body")),
                new Expectation(request().withPath(calculatePath("some_forward_path")))
                    .thenForward(forward())
            )
        );
    }

}
