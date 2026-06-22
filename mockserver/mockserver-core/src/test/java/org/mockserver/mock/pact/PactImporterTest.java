package org.mockserver.mock.pact;

import org.junit.Test;
import org.mockserver.imports.ImportRedaction;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class PactImporterTest {

    private final PactImporter importer = new PactImporter();

    private static HttpRequest req(Expectation expectation) {
        return (HttpRequest) expectation.getHttpRequest();
    }

    private static HttpResponse res(Expectation expectation) {
        return expectation.getHttpResponse();
    }

    @Test
    public void importsTwoInteractionsAsTwoExpectations() {
        String pact = "{"
            + "\"consumer\":{\"name\":\"c\"},"
            + "\"provider\":{\"name\":\"p\"},"
            + "\"interactions\":["
            + "  {"
            + "    \"description\":\"get users\","
            + "    \"request\":{\"method\":\"GET\",\"path\":\"/users\",\"query\":{\"page\":[\"1\"]},\"headers\":{\"Accept\":[\"application/json\"]}},"
            + "    \"response\":{\"status\":200,\"headers\":{\"Content-Type\":[\"application/json\"]},\"body\":{\"users\":[]}}"
            + "  },"
            + "  {"
            + "    \"description\":\"create user\","
            + "    \"request\":{\"method\":\"POST\",\"path\":\"/users\",\"body\":{\"name\":\"alice\"}},"
            + "    \"response\":{\"status\":201,\"body\":{\"id\":1}}"
            + "  }"
            + "],"
            + "\"metadata\":{\"pactSpecification\":{\"version\":\"3.0.0\"}}"
            + "}";

        List<Expectation> expectations = importer.importExpectations(pact, ImportRedaction.Options.disabled());

        assertEquals(2, expectations.size());

        Expectation getUsers = expectations.get(0);
        assertEquals("get users", getUsers.getId());
        assertEquals("GET", req(getUsers).getMethod().getValue());
        assertEquals("/users", req(getUsers).getPath().getValue());
        assertEquals("1", req(getUsers).getFirstQueryStringParameter("page"));
        assertEquals("application/json", req(getUsers).getFirstHeader("Accept"));
        assertEquals(Integer.valueOf(200), res(getUsers).getStatusCode());
        assertEquals("application/json", res(getUsers).getFirstHeader("Content-Type"));
        assertTrue(res(getUsers).getBodyAsString().contains("\"users\""));

        Expectation createUser = expectations.get(1);
        assertEquals("create user", createUser.getId());
        assertEquals("POST", req(createUser).getMethod().getValue());
        assertEquals("/users", req(createUser).getPath().getValue());
        assertTrue(req(createUser).getBodyAsString().contains("alice"));
        assertEquals(Integer.valueOf(201), res(createUser).getStatusCode());
        assertTrue(res(createUser).getBodyAsString().contains("\"id\""));
    }

    @Test
    public void importedExpectationMatchesDocumentedRequest() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"get user 1\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/users/1\"},"
            + "  \"response\":{\"status\":200,\"body\":{\"id\":1}}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        // the generated request matcher matches the documented method/path
        assertTrue(req(expectation).matches("GET", "/users/1"));
        // and the response carries the documented status and body
        assertEquals(Integer.valueOf(200), res(expectation).getStatusCode());
        assertTrue(res(expectation).getBodyAsString().contains("\"id\""));
    }

    @Test
    public void regexMatchingRuleOnPathMapsToRegexMatcherValue() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"get any user\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/users/123\"},"
            + "  \"response\":{\"status\":200},"
            + "  \"matchingRules\":{\"request\":{\"path\":{\"matchers\":[{\"match\":\"regex\",\"regex\":\"/users/\\\\d+\"}]}}}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        // the path matcher value should be the regex, not the concrete example value
        assertEquals("/users/\\d+", req(expectation).getPath().getValue());
    }

    @Test
    public void typeMatchingRuleOnHeaderRelaxesToPresenceRegex() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"auth header any value\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/secure\",\"headers\":{\"X-Trace\":[\"abc123\"]}},"
            + "  \"response\":{\"status\":200},"
            + "  \"matchingRules\":{\"request\":{\"header\":{\"$['X-Trace'][0]\":{\"matchers\":[{\"match\":\"type\"}]}}}}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertEquals(".+", req(expectation).getFirstHeader("X-Trace"));
    }

    @Test
    public void includeMatchingRuleOnQueryMapsToSubstringRegex() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"query contains\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/search\",\"query\":{\"q\":[\"hello world\"]}},"
            + "  \"response\":{\"status\":200},"
            + "  \"matchingRules\":{\"request\":{\"query\":{\"$.q[0]\":{\"matchers\":[{\"match\":\"include\",\"value\":\"world\"}]}}}}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertTrue(req(expectation).getFirstQueryStringParameter("q").contains("world"));
        assertTrue(req(expectation).getFirstQueryStringParameter("q").startsWith(".*"));
    }

    @Test
    public void redactionMasksSensitiveHeadersByDefault() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"with auth\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/x\",\"headers\":{\"Authorization\":[\"Bearer secret-token\"]}},"
            + "  \"response\":{\"status\":200}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact).get(0);

        String authValue = req(expectation).getFirstHeader("Authorization");
        assertNotNull(authValue);
        assertTrue("expected redacted Authorization but was: " + authValue, !authValue.contains("secret-token"));
    }

    @Test
    public void roundTripExportThenImportYieldsMatchingExpectations() {
        Expectation original = new Expectation(
            request().withMethod("GET").withPath("/users")
                .withQueryStringParameter("page", "1")
                .withHeader("Accept", "application/json")
        ).withId("getUsers").thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"users\":[]}")
        );

        String pact = new PactExporter().export(List.of(original), "c", "p");
        List<Expectation> reimported = importer.importExpectations(pact, ImportRedaction.Options.disabled());

        assertEquals(1, reimported.size());
        Expectation roundTripped = reimported.get(0);
        assertEquals("GET", req(roundTripped).getMethod().getValue());
        assertEquals("/users", req(roundTripped).getPath().getValue());
        assertEquals("1", req(roundTripped).getFirstQueryStringParameter("page"));
        assertEquals("application/json", req(roundTripped).getFirstHeader("Accept"));
        assertEquals(Integer.valueOf(200), res(roundTripped).getStatusCode());
        assertEquals("application/json", res(roundTripped).getFirstHeader("Content-Type"));
        assertTrue(res(roundTripped).getBodyAsString().contains("\"users\""));
    }

    @Test
    public void jsonRequestBodyMatchesRegardlessOfKeyOrder() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"create user\","
            + "  \"request\":{\"method\":\"POST\",\"path\":\"/users\",\"body\":{\"name\":\"alice\",\"age\":30}},"
            + "  \"response\":{\"status\":201}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        // the request body should be a JSON (semantic) matcher, not an exact string matcher
        assertTrue(req(expectation).getBody() instanceof org.mockserver.model.JsonBody);

        // a semantically-equal body with reordered keys / different whitespace still matches
        HttpRequest reordered = request().withMethod("POST").withPath("/users")
            .withBody("{ \"age\": 30, \"name\": \"alice\" }");
        org.mockserver.matchers.HttpRequestPropertiesMatcher matcher =
            new org.mockserver.matchers.HttpRequestPropertiesMatcher(
                org.mockserver.configuration.Configuration.configuration(),
                new org.mockserver.logging.MockServerLogger());
        matcher.update(expectation);
        assertTrue("reordered JSON body should still match", matcher.matches(null, reordered));
    }

    @Test
    public void stringRequestBodyKeptAsExactStringMatch() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"plain text body\","
            + "  \"request\":{\"method\":\"POST\",\"path\":\"/echo\",\"body\":\"hello world\"},"
            + "  \"response\":{\"status\":200}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertTrue(req(expectation).getBody() instanceof org.mockserver.model.StringBody);
        assertEquals("hello world", req(expectation).getBodyAsString());
    }

    @Test
    public void shortFieldNameDoesNotFalseMatchLongerRuleKey() {
        // field "q" must NOT pick up the rule keyed on "$.query[0]"
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"short query field\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/search\",\"query\":{\"q\":[\"abc\"]}},"
            + "  \"response\":{\"status\":200},"
            + "  \"matchingRules\":{\"request\":{\"query\":{\"$.query[0]\":{\"matchers\":[{\"match\":\"type\"}]}}}}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        // no rule should apply to "q", so the concrete example value is kept (not relaxed to ".+")
        assertEquals("abc", req(expectation).getFirstQueryStringParameter("q"));
    }

    @Test
    public void rejectsContractWithNoInteractions() {
        try {
            importer.importExpectations("{\"interactions\":[]}");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("interactions"));
        }
    }

    @Test
    public void rejectsBlankBody() {
        try {
            importer.importExpectations("   ");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("required"));
        }
    }

    @Test
    public void rejectsMalformedJson() {
        try {
            importer.importExpectations("{ not json");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("parse"));
        }
    }

    // ---- provider states ----

    @Test
    public void importsV3ProviderStateAsScenarioGate() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"get user 1\","
            + "  \"providerStates\":[{\"name\":\"a user with id 1 exists\",\"params\":{\"id\":1}}],"
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/users/1\"},"
            + "  \"response\":{\"status\":200,\"body\":{\"id\":1}}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertEquals(PactProviderStates.SCENARIO_NAME, expectation.getScenarioName());
        assertEquals("a user with id 1 exists", expectation.getScenarioState());
    }

    @Test
    public void importsV2ProviderStateAsScenarioGate() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"get user 1\","
            + "  \"providerState\":\"a user with id 1 exists\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/users/1\"},"
            + "  \"response\":{\"status\":200}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertEquals(PactProviderStates.SCENARIO_NAME, expectation.getScenarioName());
        assertEquals("a user with id 1 exists", expectation.getScenarioState());
    }

    @Test
    public void statelessInteractionHasNoScenarioGate() {
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"get users\","
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/users\"},"
            + "  \"response\":{\"status\":200}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertNull(expectation.getScenarioName());
        assertNull(expectation.getScenarioState());
    }

    @Test
    public void providerStatePreservedThroughRedaction() {
        // redaction (default) rebuilds the expectation — the provider-state gate must survive
        String pact = "{"
            + "\"interactions\":[{"
            + "  \"description\":\"secured user\","
            + "  \"providerStates\":[{\"name\":\"a user exists\"}],"
            + "  \"request\":{\"method\":\"GET\",\"path\":\"/users/1\",\"headers\":{\"Authorization\":[\"Bearer secret-token\"]}},"
            + "  \"response\":{\"status\":200}"
            + "}]}";

        Expectation expectation = importer.importExpectations(pact).get(0);

        // sensitive header redacted ...
        assertTrue(!req(expectation).getFirstHeader("Authorization").contains("secret-token"));
        // ... but the provider-state gate is preserved
        assertEquals(PactProviderStates.SCENARIO_NAME, expectation.getScenarioName());
        assertEquals("a user exists", expectation.getScenarioState());
    }

    @Test
    public void providerStateRoundTripsThroughExportThenImport() {
        Expectation original = new Expectation(
            request().withMethod("GET").withPath("/users/1")
        ).withId("getUser")
            .withScenarioName(PactProviderStates.SCENARIO_NAME)
            .withScenarioState("a user exists")
            .thenRespond(response().withStatusCode(200));

        String pact = new PactExporter().export(List.of(original), "c", "p");
        Expectation reimported = importer.importExpectations(pact, ImportRedaction.Options.disabled()).get(0);

        assertEquals(PactProviderStates.SCENARIO_NAME, reimported.getScenarioName());
        assertEquals("a user exists", reimported.getScenarioState());
    }
}
