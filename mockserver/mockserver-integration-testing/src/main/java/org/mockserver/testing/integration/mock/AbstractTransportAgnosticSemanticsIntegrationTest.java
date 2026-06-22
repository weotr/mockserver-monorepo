package org.mockserver.testing.integration.mock;

import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.java.ExpectationToJavaSerializer;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.ConfigurationProperties.maxFutureTimeout;
import static org.mockserver.matchers.Times.exactly;
import static org.mockserver.matchers.Times.unlimited;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.*;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.Parameter.schemaParam;

/**
 * Transport-agnostic matching/priority/TTL/verify/retrieve semantic tests.
 * <p>
 * These tests exercise MockServer expectation-matching semantics (path-only
 * matching, priority ordering, TTL expiry, verify, retrieve) that are
 * transport-decode-independent.  They run once (against a single transport)
 * rather than per-transport, because they do NOT exercise request body /
 * header / cookie / query WIRE decoding.
 * <p>
 * Extracted from {@link AbstractBasicMockingIntegrationTest} and
 * {@link AbstractExtendedMockingIntegrationTest} as part of integration-test
 * inheritance decomposition step 2.
 *
 * @see AbstractBasicMockingIntegrationTest
 * @see AbstractExtendedMockingIntegrationTest
 * @see AbstractControlPlaneIntegrationTest
 */
public abstract class AbstractTransportAgnosticSemanticsIntegrationTest extends AbstractMockingIntegrationTestBase {

    protected HttpResponse localNotFoundResponse() {
        return response()
            .withStatusCode(NOT_FOUND_404.code())
            .withReasonPhrase(NOT_FOUND_404.reasonPhrase());
    }

    // ========================================================================
    // From AbstractBasicMockingIntegrationTest (6 methods)
    // ========================================================================

    // --- Basic response matching ---

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

    @Test
    public void shouldReturnResponseByMatchingSchemaPathAndSchemaMethod() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethodSchema("{" + NEW_LINE +
                        "   \"type\": \"string\"," + NEW_LINE +
                        "   \"pattern\": \"^PO[A-Z]{2}$\"" + NEW_LINE +
                        "}")
                    .withPathSchema("{" + NEW_LINE +
                        "   \"type\": \"string\"," + NEW_LINE +
                        "   \"pattern\": \"some_[a-z]{4}$\"" + NEW_LINE +
                        "}")
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

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_other_path"))
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("PUT"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingSchemaPathVariable() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath("/some/path/{variableOne}/{variableTwo}")
                    .withPathParameters(
                        schemaParam("variableO[a-z]{2}", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableOneV[a-z]{4}$\"" + NEW_LINE +
                            "}"),
                        schemaParam("variableTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableTwoV[a-z]{4}$\"" + NEW_LINE +
                            "}")
                    )
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
                    .withPath(calculatePath("some/path/variableOneValue/variableTwoValue")),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/other/path")),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path/variableOneValue/variableTwoOtherValue")),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path/variableOneOtherValue/variableTwoValue")),
                getHeadersToRemove()
            )
        );
    }

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

    // ========================================================================
    // From AbstractExtendedMockingIntegrationTest (24 methods)
    // ========================================================================

    // --- Path matching ---

    @Test
    public void shouldReturnResponseByMatchingPath() {
        // when
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

        // then
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
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body1"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path1")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathExactTimes() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
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
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    // --- Optional schema query string parameter (params in path string) ---

    @Test
    public void shouldReturnResponseByMatchingOptionalSchemaQueryStringParameter() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath("/some/path")
                    .withQueryStringParameters(
                        schemaParam("?variableO[a-z]{2}", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableOneV[a-z]{4}$\"" + NEW_LINE +
                            "}"),
                        schemaParam("?variableTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableTwoV[a-z]{4}$\"" + NEW_LINE +
                            "}")
                    )
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
                    .withPath(calculatePath("some/path?variableOne=variableOneValue&variableTwo=variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableOne=variableOneValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableTwo=variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?otherVariable=otherVariableValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path")),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableOne=otherVariableValue&variableTwo=otherVariableValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableOne=otherVariableValue&variableTwo=variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableOne=variableOneValue&variableTwo=otherVariableValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableOne=otherVariableValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path?variableTwo=otherVariableValue")),
                getHeadersToRemove()
            )
        );
    }

    // --- Header not present (name presence, not value content) ---

    @Test
    public void shouldReturnResponseByMatchingHeaderNotPresent() {
        // when
        mockServerClient
            .when(
                request()
                    .withHeader(not("Authorization"), string(".*"))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("Authorization", "some_value"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("Authorization", "some_value")
                    .withHeader("SomeHeader", "some_other_value"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("NotAuthorization", "some_value")
                    .withHeader("SomeHeader", "some_other_value"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("SomeHeader", "some_other_value"),
                getHeadersToRemove()
            )
        );
    }

    // --- Creation-order + exact times ---

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfCreationExactTimes() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("some_body_one")
            );
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("some_body_two")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfCreationBeforeExpiry() throws InterruptedException {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                unlimited(),
                TimeToLive.exactly(SECONDS, 2L)
            )
            .respond(
                response()
                    .withBody("some_body_one")
            );
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                unlimited(),
                TimeToLive.exactly(SECONDS, 4L)
            )
            .respond(
                response()
                    .withBody("some_body_two")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        MILLISECONDS.sleep(2500);
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        MILLISECONDS.sleep(2250);
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    // --- Priority ordering ---

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfPriorityExactTimes() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(1),
                TimeToLive.unlimited(),
                0
            )
            .respond(
                response()
                    .withBody("some_body_one")
            );
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(1),
                TimeToLive.unlimited(),
                10
            )
            .respond(
                response()
                    .withBody("some_body_two")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfPriorityWithNegativePriorities() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(1),
                TimeToLive.unlimited(),
                -10
            )
            .respond(
                response()
                    .withBody("some_body_one")
            );
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path")),
                exactly(1),
                TimeToLive.unlimited(),
                0
            )
            .respond(
                response()
                    .withBody("some_body_two")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfPriorityWithPriorityUpdate() {
        // when
        Expectation expectationOne = new Expectation(request().withPath(calculatePath("some_path")), unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(
                response()
                    .withBody("some_body_one")
            )
            .withId("one");
        Expectation expectationTwo = new Expectation(request().withPath(calculatePath("some_path")), unlimited(), TimeToLive.unlimited(), 10)
            .thenRespond(
                response()
                    .withBody("some_body_two")
            )
            .withId("two");
        Expectation[] upsertedExpectations = mockServerClient
            .upsert(
                expectationOne,
                expectationTwo
            );

        // then
        assertThat(upsertedExpectations.length, is(2));
        assertThat(upsertedExpectations[0], is(expectationOne));
        assertThat(upsertedExpectations[1], is(expectationTwo));
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );

        // when
        Expectation expectationOneWithHigherPriority = new Expectation(request().withPath(calculatePath("some_path")), unlimited(), TimeToLive.unlimited(), 15)
            .thenRespond(
                response()
                    .withBody("some_body_one")
            )
            .withId("one");
        upsertedExpectations = mockServerClient
            .upsert(
                expectationOneWithHigherPriority
            );

        // then
        assertThat(upsertedExpectations.length, is(1));
        assertThat(upsertedExpectations[0], is(expectationOneWithHigherPriority));
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfPriorityWithPriorityUpdateAndExactTimes() {
        // when
        Expectation expectationOne = new Expectation(request().withPath(calculatePath("some_path")), exactly(1), TimeToLive.unlimited(), 0)
            .thenRespond(
                response()
                    .withBody("some_body_one")
            );
        Expectation expectationTwo = new Expectation(request().withPath(calculatePath("some_path")), exactly(1), TimeToLive.unlimited(), 10)
            .thenRespond(
                response()
                    .withBody("some_body_two")
            );
        Expectation[] upsertedExpectations = mockServerClient
            .upsert(
                expectationOne,
                expectationTwo
            );

        // then
        assertThat(upsertedExpectations.length, is(2));
        assertThat(upsertedExpectations[0], is(expectationOne));
        assertThat(upsertedExpectations[1], is(expectationTwo));
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );

        // when
        Expectation expectationOneWithHigherPriority = new Expectation(request().withPath(calculatePath("some_path")), exactly(1), TimeToLive.unlimited(), 15)
            .withId(upsertedExpectations[0].getId())
            .thenRespond(
                response()
                    .withBody("some_body_one")
            );
        upsertedExpectations = mockServerClient
            .upsert(
                expectationOneWithHigherPriority,
                expectationTwo
            );

        // then
        assertThat(upsertedExpectations.length, is(2));
        assertThat(upsertedExpectations[0], is(expectationOneWithHigherPriority));
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathInOrderOfInsertionAfterUpdate() {
        // when
        Expectation expectationOne = new Expectation(request().withPath(calculatePath("some_path")), exactly(2), TimeToLive.unlimited(), 0)
            .thenRespond(
                response()
                    .withBody("some_body_one")
            );
        Expectation expectationTwo = new Expectation(request().withPath(calculatePath("some_path")), exactly(1), TimeToLive.unlimited(), 0)
            .thenRespond(
                response()
                    .withBody("some_body_two")
            );
        Expectation[] upsertedExpectations = mockServerClient
            .upsert(
                expectationOne,
                expectationTwo
            );

        // then
        assertThat(upsertedExpectations.length, is(2));
        assertThat(upsertedExpectations[0], is(expectationOne));
        assertThat(upsertedExpectations[1], is(expectationTwo));
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );

        // when
        Expectation expectationOneWithDifferentBody = new Expectation(request().withPath(calculatePath("some_path")), exactly(1), TimeToLive.unlimited(), 0)
            .withId(upsertedExpectations[0].getId())
            .withCreated(upsertedExpectations[0].getCreated())
            .thenRespond(
                response()
                    .withBody("some_body_one_updated")
            );
        upsertedExpectations = mockServerClient
            .upsert(
                expectationOneWithDifferentBody,
                expectationTwo
            );

        // then
        assertThat(upsertedExpectations.length, is(2));
        assertThat(upsertedExpectations[0], is(expectationOneWithDifferentBody));
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one_updated"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    // --- Upsert / update ---

    @Test
    public void shouldUpdateExistingExpectation() {
        // when
        Expectation expectationOne = new Expectation(request().withPath(calculatePath("some_path_one")))
            .thenRespond(
                response()
                    .withBody("some_body_one")
            );
        Expectation expectationTwo = new Expectation(request().withPath(calculatePath("some_path_two")))
            .thenRespond(
                response()
                    .withBody("some_body_two")
            );
        mockServerClient
            .upsert(
                expectationOne,
                expectationTwo
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path_one")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path_two")),
                getHeadersToRemove()
            )
        );

        // when
        Expectation expectationOneUpdated = new Expectation(request().withPath(calculatePath("some_path_updated")))
            .thenRespond(
                response()
                    .withBody("some_body_one_updated")
            );
        mockServerClient
            .upsert(
                expectationOneUpdated
            );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_one_updated"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path_updated")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_two"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path_two")),
                getHeadersToRemove()
            )
        );
    }

    // --- TTL ---

    @Test
    public void shouldReturnResponseWhenTimeToLiveHasNotExpired() {
        // when
        mockServerClient
            .when(
                request().withPath(calculatePath("some_path")),
                exactly(1),
                TimeToLive.exactly(TimeUnit.HOURS, 1L)
            )
            .respond(
                response().withBody("some_body")
            );

        // then
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
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForWhenTimeToLiveExpired() {
        // when
        mockServerClient
            .when(
                request().withPath(calculatePath("some_path")),
                exactly(2),
                TimeToLive.exactly(SECONDS, 3L)
            )
            .respond(
                response().withBody("some_body").withDelay(SECONDS, 3L)
            );

        // then
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
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    // --- Not-operator on path and method ---

    @Test
    public void shouldReturnResponseByNotMatchingPathWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(not(calculatePath("some_path")))
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
                    .withPath(calculatePath("some_other_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingMethodWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod(not("GET"))
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
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForMatchingPathWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(not(calculatePath("some_path")))
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
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForMatchingMethodWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod(not("GET"))
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
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET"),
                getHeadersToRemove()
            )
        );
    }

    // --- Verify sequence including non-matching ---

    @Test
    public void shouldVerifySequenceOfRequestsReceivedIncludingThoseNotMatchingAnException() {
        // when
        mockServerClient.when(request().withPath(calculatePath("some_path.*")), exactly(4)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response("some_body"),
            makeRequest(
                request().withPath(calculatePath("some_path_one")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request().withPath(calculatePath("not_found")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body"),
            makeRequest(
                request().withPath(calculatePath("some_path_three")),
                getHeadersToRemove()
            )
        );
        mockServerClient.verify(request(calculatePath("some_path_one")), request(calculatePath("some_path_three")));
        mockServerClient.verify(request(calculatePath("some_path_one")), request(calculatePath("not_found")));
        mockServerClient.verify(request(calculatePath("some_path_one")), request(calculatePath("not_found")), request(calculatePath("some_path_three")));
        mockServerClient.verify(request(calculatePath("some_path_one")), request(calculatePath("not_found")), request(calculatePath("some_path_three")));
    }

    // --- Retrieve recorded requests as JSON ---

    @Test
    public void shouldRetrieveRecordedRequestsAsJson() {
        // when
        mockServerClient.when(request().withPath(calculatePath("some_path.*")), exactly(4)).respond(response().withBody("some_body"));
        assertEquals(
            response("some_body"),
            makeRequest(
                request().withPath(calculatePath("some_path_one")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request().withPath(calculatePath("not_found")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body"),
            makeRequest(
                request().withPath(calculatePath("some_path_three")),
                getHeadersToRemove()
            )
        );

        // then
        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withPath(calculatePath("some_path.*")), Format.JSON)),
            request(calculatePath("some_path_one")),
            request(calculatePath("some_path_three"))
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request(), Format.JSON)),
            request(calculatePath("some_path_one")),
            request(calculatePath("not_found")),
            request(calculatePath("some_path_three"))
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(null, Format.JSON)),
            request(calculatePath("some_path_one")),
            request(calculatePath("not_found")),
            request(calculatePath("some_path_three"))
        );
    }

    // --- Retrieve active expectations as JSON / Java ---

    @Test
    public void shouldRetrieveActiveExpectationsAsJson() {
        // when
        mockServerClient.when(request().withPath(calculatePath("some_path.*")), exactly(4))
            .respond(response().withBody("some_body"));
        mockServerClient.when(request().withPath(calculatePath("some_path.*")))
            .respond(response().withBody("some_body"));
        mockServerClient.when(request().withPath(calculatePath("some_other_path")))
            .respond(response().withBody("some_other_body"));
        mockServerClient.when(request().withPath(calculatePath("some_forward_path")))
            .forward(forward());

        // then
        assertThat(
            new ExpectationSerializer(new MockServerLogger())
                .deserializeArray(
                    mockServerClient
                        .retrieveActiveExpectations(request().withPath(calculatePath("some_path.*")), Format.JSON),
                    false
                ),
            arrayContaining(
                new Expectation(request().withPath(calculatePath("some_path.*")), exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body"))
            )
        );

        assertThat(
            new ExpectationSerializer(new MockServerLogger())
                .deserializeArray(
                    mockServerClient
                        .retrieveActiveExpectations(null, Format.JSON),
                    false
                ),
            arrayContaining(
                new Expectation(request().withPath(calculatePath("some_path.*")), exactly(4), TimeToLive.unlimited(), 0)
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
            new ExpectationSerializer(new MockServerLogger())
                .deserializeArray(
                    mockServerClient
                        .retrieveActiveExpectations(request(), Format.JSON),
                    false
                ),
            arrayContaining(
                new Expectation(request().withPath(calculatePath("some_path.*")), exactly(4), TimeToLive.unlimited(), 0)
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

    @Test
    public void shouldRetrieveActiveExpectationsAsJava() {
        // when
        mockServerClient.when(request().withPath(calculatePath("some_path.*")), exactly(4))
            .respond(response().withBody("some_body"));
        mockServerClient.when(request().withPath(calculatePath("some_path.*")))
            .respond(response().withBody("some_body"));
        mockServerClient.when(request().withPath(calculatePath("some_other_path")))
            .respond(response().withBody("some_other_body"));
        mockServerClient.when(request().withPath(calculatePath("some_forward_path")))
            .forward(forward());

        // then
        assertThat(
            mockServerClient.retrieveActiveExpectations(request().withPath(calculatePath("some_path.*")), Format.JAVA),
            is(new ExpectationToJavaSerializer().serialize(Arrays.asList(
                new Expectation(request().withPath(calculatePath("some_path.*")), exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body"))
            )))
        );

        assertThat(
            mockServerClient.retrieveActiveExpectations(null, Format.JAVA),
            is(new ExpectationToJavaSerializer().serialize(Arrays.asList(
                new Expectation(request().withPath(calculatePath("some_path.*")), exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_other_path")))
                    .thenRespond(response().withBody("some_other_body")),
                new Expectation(request().withPath(calculatePath("some_forward_path")))
                    .thenForward(forward())
            )))
        );

        assertThat(
            mockServerClient.retrieveActiveExpectations(request(), Format.JAVA),
            is(new ExpectationToJavaSerializer().serialize(Arrays.asList(
                new Expectation(request().withPath(calculatePath("some_path.*")), exactly(4), TimeToLive.unlimited(), 0)
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_path.*")))
                    .thenRespond(response().withBody("some_body")),
                new Expectation(request().withPath(calculatePath("some_other_path")))
                    .thenRespond(response().withBody("some_other_body")),
                new Expectation(request().withPath(calculatePath("some_forward_path")))
                    .thenForward(forward())
            )))
        );
    }

    // --- Concurrency / interruption ---

    @Test
    public void shouldEnsureThatInterruptedRequestsAreVerifiable() {
        mockServerClient
            .when(
                request(calculatePath("delayed"))
            )
            .respond(
                response("delayed data")
                    .withDelay(new Delay(SECONDS, 3))
            );

        Future<HttpResponse> delayedFuture = Executors.newSingleThreadExecutor().submit(() -> httpClient.sendRequest(
            request(addContextToPath(calculatePath("delayed")))
                .withHeader(HOST.toString(), "localhost:" + getServerPort())
        ).get(10, SECONDS));

        Uninterruptibles.sleepUninterruptibly(1, SECONDS); // Let request reach server

        delayedFuture.cancel(true); // Then interrupt requesting thread

        mockServerClient.verify(request(calculatePath("delayed"))); // We should be able to verify request that reached server even though its later interrupted
    }

    @Test
    public void shouldEnsureThatRequestDelaysDoNotAffectOtherRequests() throws Exception {
        mockServerClient
            .when(
                request("/slow")
            )
            .respond(
                response("super slow")
                    .withDelay(new Delay(SECONDS, 5))
            );
        mockServerClient
            .when(
                request("/fast")
            )
            .respond(
                response("quite fast")
            );

        ExecutorService executorService = Executors.newFixedThreadPool(2);

        Future<Long> slowFuture = executorService.submit(() -> {
            long start = System.currentTimeMillis();
            makeRequest(request("/slow"), Collections.emptySet());
            return System.currentTimeMillis() - start;
        });

        // Let fast request come to the server slightly after slow request
        Uninterruptibles.sleepUninterruptibly(1, SECONDS);

        Future<Long> fastFuture = executorService.submit(() -> {
            long start = System.currentTimeMillis();
            makeRequest(request("/fast"), Collections.emptySet());
            return System.currentTimeMillis() - start;

        });

        Long slowRequestElapsedMillis = slowFuture.get(maxFutureTimeout(), MILLISECONDS);
        Long fastRequestElapsedMillis = fastFuture.get(maxFutureTimeout(), MILLISECONDS);

        assertThat("Slow request takes less than expected", slowRequestElapsedMillis, is(greaterThan(4 * 1000L)));
        assertThat("Fast request takes longer than expected", fastRequestElapsedMillis, is(lessThan(1000L)));
    }

}
