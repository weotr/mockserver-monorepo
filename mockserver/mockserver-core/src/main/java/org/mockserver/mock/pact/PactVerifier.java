package org.mockserver.mock.pact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Parameter;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Verifies that MockServer's currently-active expectations satisfy each interaction
 * in a Pact v3 consumer contract. This closes the consumer-driven-contract loop:
 * the consumer can export a contract via {@link PactExporter}, and the provider
 * (MockServer) can verify that its expectations produce the expected responses.
 *
 * <p>For each interaction, the verifier:
 * <ol>
 *   <li>Builds an {@link HttpRequest} from the interaction's {@code request} fields
 *       (method, path, query, headers, body) — mirroring how {@link PactExporter}
 *       maps fields, in reverse.</li>
 *   <li>Finds matching active expectations via the matching engine
 *       ({@link RequestMatchers#retrieveExpectationsMatchingRequest}).</li>
 *   <li>Compares the first matched expectation's response to the interaction's
 *       expected response (status, headers subset, body).</li>
 * </ol>
 *
 * <p>Only expectations with a static {@link HttpResponse} action are verifiable.
 * Forward, callback, and template actions are flagged as "unverifiable (non-static action)".
 */
public class PactVerifier {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    /**
     * Verifies the given Pact v3 contract JSON against the active expectations
     * in the provided {@link RequestMatchers}.
     *
     * @param pactJson        the Pact v3 contract as a JSON string
     * @param requestMatchers the active request matchers to verify against
     * @return a {@link PactVerificationResult} containing per-interaction results
     * @throws IllegalArgumentException if the Pact JSON is malformed or contains no interactions
     */
    public PactVerificationResult verify(String pactJson, RequestMatchers requestMatchers) {
        if (isBlank(pactJson)) {
            throw new IllegalArgumentException("Pact contract JSON must not be empty");
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(pactJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Pact contract JSON is malformed: " + e.getMessage());
        }

        JsonNode interactionsNode = root.get("interactions");
        if (interactionsNode == null || !interactionsNode.isArray() || interactionsNode.isEmpty()) {
            throw new IllegalArgumentException("Pact contract must contain at least one interaction");
        }

        List<InteractionResult> results = new ArrayList<>();
        boolean allVerified = true;

        // Snapshot the live Pact provider-state scenario so verification (which activates each
        // interaction's provider state as it goes) leaves the data-plane scenario unchanged.
        final org.mockserver.mock.ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
        final String priorState = scenarioManager.getState(PactProviderStates.SCENARIO_NAME);

        try {
            for (JsonNode interactionNode : interactionsNode) {
                String description = interactionNode.has("description")
                    ? interactionNode.get("description").asText()
                    : "(unnamed interaction)";

                JsonNode requestNode = interactionNode.get("request");
                if (requestNode == null) {
                    results.add(new InteractionResult(description, false, "interaction has no request definition"));
                    allVerified = false;
                    continue;
                }

                HttpRequest pactRequest = buildHttpRequest(requestNode);

                // Honour the interaction's provider state ("given ...") before matching: the
                // provider-state callback. Activating it sets the well-known Pact scenario into the
                // required state so that a state-gated expectation imported from this contract
                // becomes serviceable (to this verification and to concurrent data-plane traffic).
                String providerState = PactProviderStates.gatingStateOf(interactionNode);
                PactProviderStates.activate(scenarioManager, providerState);

                // Find matching expectations using the read-only forward-matching method.
                List<Expectation> matchingExpectations = requestMatchers.retrieveExpectationsMatchingRequest(pactRequest);

                // retrieveExpectationsMatchingRequest matches on request fields only and does NOT
                // apply the scenario gate, so a scenario-gated expectation could be returned even
                // when its required provider state is not active. Filter to expectations whose
                // provider state is satisfied (stateless expectations always pass) so verification
                // respects provider states.
                Expectation matchedExpectation = firstWithSatisfiedState(matchingExpectations, providerState);

                if (matchedExpectation == null) {
                    String reason;
                    if (matchingExpectations.isEmpty()) {
                        reason = "no matching expectation found";
                    } else if (providerState != null) {
                        reason = "no matching expectation found for provider state '" + providerState + "'";
                    } else {
                        reason = "matching expectation found but it requires a provider state not declared by this interaction";
                    }
                    results.add(new InteractionResult(description, false, reason));
                    allVerified = false;
                    continue;
                }

                // Check that the expectation has a static response action
                HttpResponse expectedMockResponse = representativeResponse(matchedExpectation);
                if (expectedMockResponse == null) {
                    results.add(new InteractionResult(description, false,
                        "unverifiable (non-static action) — only response expectations can be verified against a Pact contract"));
                    allVerified = false;
                    continue;
                }

                // Compare the Pact interaction's expected response with the expectation's response
                JsonNode pactResponseNode = interactionNode.get("response");
                if (pactResponseNode == null) {
                    results.add(new InteractionResult(description, false, "interaction has no response definition"));
                    allVerified = false;
                    continue;
                }

                String mismatchReason = compareResponses(pactResponseNode, expectedMockResponse);
                if (mismatchReason != null) {
                    results.add(new InteractionResult(description, false, mismatchReason));
                    allVerified = false;
                } else {
                    results.add(new InteractionResult(description, true, null));
                }
            }
        } finally {
            // Restore the Pact provider-state scenario to whatever it was before verification, so a
            // read-only verify() never leaves residual state on the live data-plane matcher.
            if (org.mockserver.mock.ScenarioManager.STARTED.equals(priorState)) {
                scenarioManager.clear(PactProviderStates.SCENARIO_NAME);
            } else {
                scenarioManager.setState(PactProviderStates.SCENARIO_NAME, priorState);
            }
        }

        return new PactVerificationResult(allVerified, results);
    }

    /**
     * Builds an {@link HttpRequest} from a Pact interaction request node.
     * Mirrors the structure used by {@link PactExporter#buildRequest} in reverse.
     */
    private HttpRequest buildHttpRequest(JsonNode requestNode) {
        HttpRequest request = HttpRequest.request();

        if (requestNode.has("method")) {
            request.withMethod(requestNode.get("method").asText());
        }
        if (requestNode.has("path")) {
            request.withPath(requestNode.get("path").asText());
        }
        if (requestNode.has("query")) {
            JsonNode queryNode = requestNode.get("query");
            Iterator<Map.Entry<String, JsonNode>> fields = queryNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String paramName = entry.getKey();
                JsonNode valuesNode = entry.getValue();
                if (valuesNode.isArray()) {
                    List<String> values = new ArrayList<>();
                    for (JsonNode v : valuesNode) {
                        values.add(v.asText());
                    }
                    request.withQueryStringParameter(new Parameter(paramName, values));
                } else {
                    request.withQueryStringParameter(paramName, valuesNode.asText());
                }
            }
        }
        if (requestNode.has("headers")) {
            JsonNode headersNode = requestNode.get("headers");
            Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String headerName = entry.getKey();
                JsonNode valuesNode = entry.getValue();
                if (valuesNode.isArray()) {
                    List<String> values = new ArrayList<>();
                    for (JsonNode v : valuesNode) {
                        values.add(v.asText());
                    }
                    request.withHeader(new Header(headerName, values));
                } else {
                    request.withHeader(headerName, valuesNode.asText());
                }
            }
        }
        if (requestNode.has("body")) {
            JsonNode bodyNode = requestNode.get("body");
            if (bodyNode.isTextual()) {
                request.withBody(bodyNode.asText());
            } else {
                // Structured JSON body — serialize back to string for matching
                request.withBody(bodyNode.toString());
            }
        }

        return request;
    }

    /**
     * Compares a Pact interaction's expected response with the matched expectation's response.
     *
     * @return a mismatch reason string, or null if the responses match
     */
    private String compareResponses(JsonNode pactResponseNode, HttpResponse mockResponse) {
        // Compare status code
        if (pactResponseNode.has("status")) {
            int expectedStatus = pactResponseNode.get("status").asInt();
            int actualStatus = mockResponse.getStatusCode() != null ? mockResponse.getStatusCode() : 200;
            if (expectedStatus != actualStatus) {
                return "status code mismatch: expected " + expectedStatus + " but was " + actualStatus;
            }
        }

        // Compare headers (subset match — each Pact header must be present)
        if (pactResponseNode.has("headers")) {
            JsonNode pactHeaders = pactResponseNode.get("headers");
            String headerMismatch = compareHeaders(pactHeaders, mockResponse);
            if (headerMismatch != null) {
                return headerMismatch;
            }
        }

        // Compare body
        if (pactResponseNode.has("body")) {
            String bodyMismatch = compareBody(pactResponseNode.get("body"), mockResponse);
            if (bodyMismatch != null) {
                return bodyMismatch;
            }
        }

        return null;
    }

    /**
     * Subset match: each header in the Pact response must be present in the MockServer
     * response with a matching value. Extra headers in MockServer are allowed.
     */
    private String compareHeaders(JsonNode pactHeaders, HttpResponse mockResponse) {
        Iterator<Map.Entry<String, JsonNode>> fields = pactHeaders.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String headerName = entry.getKey();
            JsonNode expectedValues = entry.getValue();

            List<String> actualValues = mockResponse.getHeader(headerName);
            if (actualValues == null || actualValues.isEmpty()) {
                return "header mismatch: expected header '" + headerName + "' not found in response";
            }

            if (expectedValues.isArray()) {
                for (JsonNode expectedValue : expectedValues) {
                    String expected = expectedValue.asText();
                    if (!actualValues.contains(expected)) {
                        return "header mismatch: header '" + headerName + "' expected value '" + expected
                            + "' not found (actual values: " + actualValues + ")";
                    }
                }
            } else {
                String expected = expectedValues.asText();
                if (!actualValues.contains(expected)) {
                    return "header mismatch: header '" + headerName + "' expected value '" + expected
                        + "' not found (actual values: " + actualValues + ")";
                }
            }
        }
        return null;
    }

    /**
     * Compares the Pact response body with the MockServer response body.
     * If both parse as JSON, uses structural equality; otherwise uses string equality.
     */
    private String compareBody(JsonNode pactBody, HttpResponse mockResponse) {
        String mockBodyStr = mockResponse.getBodyAsString();

        if (pactBody.isNull() || pactBody.isMissingNode()) {
            // Pact expects no body — skip body comparison
            return null;
        }

        if (isBlank(mockBodyStr)) {
            return "body mismatch: expected body but response has no body";
        }

        // If pact body is structured JSON, try to parse the mock body as JSON and compare structurally
        if (!pactBody.isTextual()) {
            try {
                JsonNode mockBodyJson = objectMapper.readTree(mockBodyStr);
                if (!pactBody.equals(mockBodyJson)) {
                    return "body mismatch: JSON bodies are not equal";
                }
                return null;
            } catch (JsonProcessingException e) {
                return "body mismatch: expected JSON body but response body is not valid JSON";
            }
        }

        // Both are plain strings
        String pactBodyStr = pactBody.asText();
        if (!pactBodyStr.equals(mockBodyStr)) {
            return "body mismatch: expected '" + truncate(pactBodyStr) + "' but was '" + truncate(mockBodyStr) + "'";
        }
        return null;
    }

    /**
     * Returns the first expectation whose provider-state gate is satisfied for the interaction being
     * verified, or null if none qualify. An expectation gated on MockServer's Pact provider-state
     * scenario ({@link PactProviderStates#SCENARIO_NAME}) only qualifies when its required state
     * equals the interaction's provider state; an expectation with no Pact provider-state gate (or
     * gated on a different scenario) always qualifies, preserving the prior stateless behaviour.
     *
     * @param matchingExpectations the request-field matches returned by the matcher
     * @param providerState        the interaction's provider state, or null when stateless
     * @return the first qualifying expectation, or null
     */
    private static Expectation firstWithSatisfiedState(List<Expectation> matchingExpectations, String providerState) {
        for (Expectation expectation : matchingExpectations) {
            if (providerStateSatisfied(expectation, providerState)) {
                return expectation;
            }
        }
        return null;
    }

    private static boolean providerStateSatisfied(Expectation expectation, String providerState) {
        if (!PactProviderStates.SCENARIO_NAME.equals(expectation.getScenarioName())) {
            // not gated on a Pact provider state — unaffected
            return true;
        }
        String required = expectation.getScenarioState();
        if (required == null) {
            return true;
        }
        return required.equals(providerState);
    }

    /**
     * Returns the single response, or the first of a response sequence, or null for non-response actions.
     * Mirrors {@link PactExporter}'s representativeResponse method.
     */
    private static HttpResponse representativeResponse(Expectation expectation) {
        if (expectation.getHttpResponse() != null) {
            return expectation.getHttpResponse();
        }
        List<HttpResponse> responses = expectation.getHttpResponses();
        return (responses != null && !responses.isEmpty()) ? responses.get(0) : null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    /**
     * The overall result of verifying a Pact contract.
     */
    public static class PactVerificationResult {
        private final boolean verified;
        private final List<InteractionResult> interactions;

        public PactVerificationResult(boolean verified, List<InteractionResult> interactions) {
            this.verified = verified;
            this.interactions = interactions;
        }

        public boolean isVerified() {
            return verified;
        }

        public List<InteractionResult> getInteractions() {
            return interactions;
        }

        /**
         * Serializes the result as a JSON string for HTTP responses.
         */
        public String toJson() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("verified", verified);
            ArrayNode interactionsArray = root.putArray("interactions");
            for (InteractionResult ir : interactions) {
                ObjectNode node = interactionsArray.addObject();
                node.put("description", ir.getDescription());
                node.put("verified", ir.isVerified());
                if (ir.getReason() != null) {
                    node.put("reason", ir.getReason());
                }
            }
            try {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("failed to serialize Pact verification result", e);
            }
        }
    }

    /**
     * The result of verifying a single Pact interaction.
     */
    public static class InteractionResult {
        private final String description;
        private final boolean verified;
        private final String reason;

        public InteractionResult(String description, boolean verified, String reason) {
            this.description = description;
            this.verified = verified;
            this.reason = reason;
        }

        public String getDescription() {
            return description;
        }

        public boolean isVerified() {
            return verified;
        }

        public String getReason() {
            return reason;
        }
    }
}
