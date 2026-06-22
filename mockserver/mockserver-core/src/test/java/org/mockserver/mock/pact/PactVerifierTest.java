package org.mockserver.mock.pact;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.scheduler.Scheduler;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * Tests for {@link PactVerifier}, verifying that active expectations
 * satisfy a Pact v3 consumer contract.
 */
public class PactVerifierTest {

    private final PactVerifier verifier = new PactVerifier();
    private final PactExporter exporter = new PactExporter();
    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, PactVerifierTest.class);
    private RequestMatchers requestMatchers;

    @Before
    public void setUp() {
        requestMatchers = new RequestMatchers(configuration, mockServerLogger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    @After
    public void tearDown() {
        requestMatchers.reset();
    }

    private void addExpectation(Expectation expectation) {
        requestMatchers.add(expectation, Cause.API);
    }

    // ---- All-pass case ----

    @Test
    public void allInteractionsVerifyWhenExpectationsMatch() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/users")
        ).withId("getUsers").thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"users\":[]}")
        ));
        addExpectation(new Expectation(
            request().withMethod("POST").withPath("/users")
        ).withId("createUser").thenRespond(
            response().withStatusCode(201)
        ));

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"test\"},\n"
            + "  \"provider\": {\"name\": \"provider\"},\n"
            + "  \"interactions\": [\n"
            + "    {\n"
            + "      \"description\": \"get users\",\n"
            + "      \"request\": {\"method\": \"GET\", \"path\": \"/users\"},\n"
            + "      \"response\": {\"status\": 200, \"headers\": {\"Content-Type\": [\"application/json\"]}, \"body\": {\"users\": []}}\n"
            + "    },\n"
            + "    {\n"
            + "      \"description\": \"create user\",\n"
            + "      \"request\": {\"method\": \"POST\", \"path\": \"/users\"},\n"
            + "      \"response\": {\"status\": 201}\n"
            + "    }\n"
            + "  ],\n"
            + "  \"metadata\": {\"pactSpecification\": {\"version\": \"3.0.0\"}}\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertTrue("all interactions should verify", result.isVerified());
        assertThat(result.getInteractions(), hasSize(2));
        assertTrue(result.getInteractions().get(0).isVerified());
        assertTrue(result.getInteractions().get(1).isVerified());
        assertNull(result.getInteractions().get(0).getReason());
    }

    // ---- Status code mismatch ----

    @Test
    public void failsWhenStatusCodeMismatches() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/health")
        ).thenRespond(
            response().withStatusCode(200)
        ));

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"health check\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/health\"},\n"
            + "    \"response\": {\"status\": 204}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("verification should fail", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("status code mismatch"));
        assertThat(result.getInteractions().get(0).getReason(), containsString("expected 204"));
        assertThat(result.getInteractions().get(0).getReason(), containsString("but was 200"));
    }

    // ---- No matching expectation ----

    @Test
    public void failsWhenNoExpectationMatchesRequest() {
        // No expectations registered

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"unknown endpoint\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/nonexistent\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("verification should fail", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("no matching expectation"));
    }

    // ---- Body mismatch ----

    @Test
    public void failsWhenJsonBodyMismatches() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/data")
        ).thenRespond(
            response().withStatusCode(200).withBody("{\"key\":\"actual\"}")
        ));

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"get data\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/data\"},\n"
            + "    \"response\": {\"status\": 200, \"body\": {\"key\": \"expected\"}}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("verification should fail", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("body mismatch"));
    }

    // ---- Header subset match passes ----

    @Test
    public void passesWhenHeaderSubsetMatches() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/api")
        ).thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Extra-Header", "extra-value")
        ));

        // Pact only requires Content-Type — the extra header in MockServer should be fine
        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"get api\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/api\"},\n"
            + "    \"response\": {\"status\": 200, \"headers\": {\"Content-Type\": [\"application/json\"]}}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertTrue("header subset match should pass", result.isVerified());
    }

    // ---- Header mismatch ----

    @Test
    public void failsWhenRequiredHeaderMissing() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/api")
        ).thenRespond(
            response().withStatusCode(200)
        ));

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"get api\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/api\"},\n"
            + "    \"response\": {\"status\": 200, \"headers\": {\"X-Required\": [\"value\"]}}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("should fail when required header is missing", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("header mismatch"));
        assertThat(result.getInteractions().get(0).getReason(), containsString("X-Required"));
    }

    // ---- Non-static action (forward) is unverifiable ----

    @Test
    public void failsWithNonStaticActionExplanation() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/proxy")
        ).thenForward(
            forward().withHost("example.com").withPort(443)
        ));

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"proxied request\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/proxy\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("should fail for non-static action", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("unverifiable"));
        assertThat(result.getInteractions().get(0).getReason(), containsString("non-static action"));
    }

    // ---- Round-trip: export then verify ----

    @Test
    public void roundTripExportThenVerifyPasses() {
        Expectation getUsers = new Expectation(
            request().withMethod("GET").withPath("/users")
                .withQueryStringParameter("page", "1")
                .withHeader("Accept", "application/json")
        ).withId("getUsers").thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"users\":[{\"name\":\"alice\"}]}")
        );

        Expectation postUser = new Expectation(
            request().withMethod("POST").withPath("/users")
                .withBody(json("{\"name\":\"bob\"}"))
        ).withId("createUser").thenRespond(
            response().withStatusCode(201)
                .withBody("{\"id\":42}")
        );

        List<Expectation> expectations = Arrays.asList(getUsers, postUser);
        for (Expectation e : expectations) {
            addExpectation(e);
        }

        // Export
        String pactJson = exporter.export(expectations, "frontend", "users-service");

        // Verify the exported contract against the same expectations
        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertTrue("round-trip export-then-verify should pass: " + result.toJson(), result.isVerified());
        assertThat(result.getInteractions(), hasSize(2));
        for (PactVerifier.InteractionResult ir : result.getInteractions()) {
            assertTrue("interaction '" + ir.getDescription() + "' should verify", ir.isVerified());
        }
    }

    // ---- Mixed pass/fail ----

    @Test
    public void mixedPassAndFailInteractions() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/ok")
        ).thenRespond(
            response().withStatusCode(200)
        ));
        // No expectation for /missing

        String pactJson = "{\n"
            + "  \"consumer\": {\"name\": \"c\"},\n"
            + "  \"provider\": {\"name\": \"p\"},\n"
            + "  \"interactions\": [\n"
            + "    {\n"
            + "      \"description\": \"ok endpoint\",\n"
            + "      \"request\": {\"method\": \"GET\", \"path\": \"/ok\"},\n"
            + "      \"response\": {\"status\": 200}\n"
            + "    },\n"
            + "    {\n"
            + "      \"description\": \"missing endpoint\",\n"
            + "      \"request\": {\"method\": \"GET\", \"path\": \"/missing\"},\n"
            + "      \"response\": {\"status\": 200}\n"
            + "    }\n"
            + "  ]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("overall should fail", result.isVerified());
        assertThat(result.getInteractions(), hasSize(2));
        assertTrue("first interaction should pass", result.getInteractions().get(0).isVerified());
        assertFalse("second interaction should fail", result.getInteractions().get(1).isVerified());
    }

    // ---- Error cases ----

    @Test(expected = IllegalArgumentException.class)
    public void throwsOnEmptyPactJson() {
        verifier.verify("", requestMatchers);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsOnNullPactJson() {
        verifier.verify(null, requestMatchers);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsOnMalformedJson() {
        verifier.verify("not json at all {{{", requestMatchers);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsOnMissingInteractions() {
        verifier.verify("{\"consumer\":{\"name\":\"c\"},\"provider\":{\"name\":\"p\"}}", requestMatchers);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsOnEmptyInteractionsArray() {
        verifier.verify("{\"interactions\":[]}", requestMatchers);
    }

    // ---- JSON result serialization ----

    @Test
    public void resultSerializesAsJson() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/x")
        ).thenRespond(
            response().withStatusCode(200)
        ));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"test\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/x\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);
        String json = result.toJson();

        assertThat(json, containsString("\"verified\" : true"));
        assertThat(json, containsString("\"description\" : \"test\""));
    }

    // ---- String body match ----

    @Test
    public void passesWhenStringBodyMatches() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/text")
        ).thenRespond(
            response().withStatusCode(200).withBody("hello world")
        ));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"text response\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/text\"},\n"
            + "    \"response\": {\"status\": 200, \"body\": \"hello world\"}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);
        assertTrue("string body should match", result.isVerified());
    }

    @Test
    public void failsWhenStringBodyMismatches() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/text")
        ).thenRespond(
            response().withStatusCode(200).withBody("hello world")
        ));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"text response\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/text\"},\n"
            + "    \"response\": {\"status\": 200, \"body\": \"goodbye world\"}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);
        assertFalse("string body mismatch should fail", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("body mismatch"));
    }

    // ---- Query parameter matching ----

    @Test
    public void verifiesInteractionWithQueryParameters() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/search")
                .withQueryStringParameter("q", "test")
        ).thenRespond(
            response().withStatusCode(200).withBody("{\"results\":[]}")
        ));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"search\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/search\", \"query\": {\"q\": [\"test\"]}},\n"
            + "    \"response\": {\"status\": 200, \"body\": {\"results\": []}}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);
        assertTrue("should verify with matching query parameters", result.isVerified());
    }

    // ---- Response sequence (uses first response) ----

    @Test
    public void verifiesAgainstFirstResponseInSequence() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/seq")
        ).thenRespond(Arrays.asList(
            response().withStatusCode(200),
            response().withStatusCode(500)
        )));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"sequence response\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/seq\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);
        assertTrue("should verify against first response in sequence", result.isVerified());
    }

    // ---- provider states ----

    @Test
    public void verifiesStateGatedExpectationWhenProviderStateActivated() {
        // a state-gated expectation (as PactImporter would produce) — only serviceable once the
        // provider state is active; the verifier must activate it before matching
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/users/1")
        ).withId("getUser1")
            .withScenarioName(PactProviderStates.SCENARIO_NAME)
            .withScenarioState("a user with id 1 exists")
            .thenRespond(response().withStatusCode(200).withBody(json("{\"id\":1}"))));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"get user 1\",\n"
            + "    \"providerStates\": [{\"name\": \"a user with id 1 exists\"}],\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/users/1\"},\n"
            + "    \"response\": {\"status\": 200, \"body\": {\"id\": 1}}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertTrue("state-gated interaction should verify when its provider state is honoured", result.isVerified());
    }

    @Test
    public void failsStateGatedExpectationWhenInteractionProvidesWrongState() {
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/users/1")
        ).withId("getUser1")
            .withScenarioName(PactProviderStates.SCENARIO_NAME)
            .withScenarioState("a user with id 1 exists")
            .thenRespond(response().withStatusCode(200)));

        // interaction declares a DIFFERENT provider state, so the gated expectation must not satisfy it
        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"get user 1\",\n"
            + "    \"providerStates\": [{\"name\": \"no users exist\"}],\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/users/1\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertFalse("wrong provider state should not satisfy a state-gated expectation", result.isVerified());
        assertThat(result.getInteractions().get(0).getReason(), containsString("provider state"));
        assertThat(result.getInteractions().get(0).getReason(), containsString("no users exist"));
    }

    @Test
    public void honoursDistinctProviderStatesAcrossInteractions() {
        // two expectations on the same request path, each gated on a different provider state,
        // returning different bodies — verification must pick the right one per interaction
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/account")
        ).withId("activeAccount")
            .withScenarioName(PactProviderStates.SCENARIO_NAME)
            .withScenarioState("account is active")
            .thenRespond(response().withStatusCode(200).withBody(json("{\"status\":\"active\"}"))));
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/account")
        ).withId("closedAccount")
            .withScenarioName(PactProviderStates.SCENARIO_NAME)
            .withScenarioState("account is closed")
            .thenRespond(response().withStatusCode(200).withBody(json("{\"status\":\"closed\"}"))));

        String pactJson = "{\n"
            + "  \"interactions\": [\n"
            + "    {\n"
            + "      \"description\": \"active account\",\n"
            + "      \"providerStates\": [{\"name\": \"account is active\"}],\n"
            + "      \"request\": {\"method\": \"GET\", \"path\": \"/account\"},\n"
            + "      \"response\": {\"status\": 200, \"body\": {\"status\": \"active\"}}\n"
            + "    },\n"
            + "    {\n"
            + "      \"description\": \"closed account\",\n"
            + "      \"providerStates\": [{\"name\": \"account is closed\"}],\n"
            + "      \"request\": {\"method\": \"GET\", \"path\": \"/account\"},\n"
            + "      \"response\": {\"status\": 200, \"body\": {\"status\": \"closed\"}}\n"
            + "    }\n"
            + "  ]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);

        assertTrue("each interaction should verify against the expectation for its provider state", result.isVerified());
        assertThat(result.getInteractions(), hasSize(2));
    }

    @Test
    public void statelessInteractionStillVerifiesAgainstUngatedExpectation() {
        // an ungated expectation must keep verifying regardless of provider-state plumbing
        addExpectation(new Expectation(
            request().withMethod("GET").withPath("/health")
        ).thenRespond(response().withStatusCode(200)));

        String pactJson = "{\n"
            + "  \"interactions\": [{\n"
            + "    \"description\": \"health check\",\n"
            + "    \"request\": {\"method\": \"GET\", \"path\": \"/health\"},\n"
            + "    \"response\": {\"status\": 200}\n"
            + "  }]\n"
            + "}";

        PactVerifier.PactVerificationResult result = verifier.verify(pactJson, requestMatchers);
        assertTrue("stateless interaction should still verify", result.isVerified());
    }
}
