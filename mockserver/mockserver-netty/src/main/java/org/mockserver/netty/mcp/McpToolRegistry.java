package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.client.LlmConversationBuilder;
import org.mockserver.client.TurnBuilder;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.llm.IsolationSource;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.analysis.AgentRunAnalyzer;
import org.mockserver.llm.client.LlmBackend;
import org.mockserver.llm.client.LlmBackendResolver;
import org.mockserver.llm.client.LlmClient;
import org.mockserver.llm.client.LlmClientRegistry;
import org.mockserver.llm.client.NettyHttpClientLlmTransport;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;
import org.mockserver.llm.drift.DriftDetector;
import org.mockserver.llm.drift.DriftReport;
import org.mockserver.matchers.MatchType;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MismatchRemediation;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.OpenAPIExpectation;
import org.mockserver.model.*;
import org.mockserver.openapi.OpenApiContractTest;
import org.mockserver.openapi.OpenApiResiliencyTest;
import org.mockserver.openapi.OpenApiTrafficValidator;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.LogEventRequestAndResponseSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.fixture.SseAwareExpectationConverter;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.event.Level;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class McpToolRegistry {

    private final HttpState httpState;
    private final LifeCycle server;
    private final ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final Map<String, ToolDefinition> tools;
    private ExpectationSerializer expectationSerializer;
    private RequestDefinitionSerializer requestDefinitionSerializer;

    public McpToolRegistry(HttpState httpState, LifeCycle server) {
        this.httpState = httpState;
        this.server = server;
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.mockServerLogger = httpState.getMockServerLogger();
        this.tools = new LinkedHashMap<>();
        registerAllTools();
    }

    private ExpectationSerializer getExpectationSerializer() {
        if (expectationSerializer == null) {
            expectationSerializer = new ExpectationSerializer(mockServerLogger);
        }
        return expectationSerializer;
    }

    private RequestDefinitionSerializer getRequestDefinitionSerializer() {
        if (requestDefinitionSerializer == null) {
            requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        }
        return requestDefinitionSerializer;
    }

    public Map<String, ToolDefinition> getTools() {
        return tools;
    }

    public JsonNode callTool(String name, JsonNode params) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            return null;
        }
        return tool.handler.apply(params != null ? params : objectMapper.createObjectNode());
    }

    private void registerAllTools() {
        registerCreateExpectation();
        registerVerifyRequest();
        registerRetrieveRecordedRequests();
        registerClearExpectations();
        registerReset();
        registerGetStatus();
        registerVerifyRequestSequence();
        registerRetrieveRequestResponses();
        registerCreateForwardExpectation();
        registerDebugRequestMismatch();
        registerExplainUnmatchedRequests();
        registerCreateExpectationFromOpenApi();
        registerCreateExpectationsFromRecordedTraffic();
        registerStopServer();
        registerRawExpectation();
        registerRawRetrieve();
        registerRawVerify();
        registerVerifyTrafficAgainstOpenApi();
        registerRunContractTest();
        registerRunResiliencyTest();
        registerRunMcpContractTest();
        registerRecordLlmFixtures();
        registerLoadExpectationsFromFile();
        registerMockLlmCompletion();
        registerCreateLlmConversation();
        registerVerifyToolCall();
        registerVerifyStructuredOutput();
        registerExplainAgentRun();
        registerDetectLlmDrift();
        registerMockAdversarialLlmResponse();
    }

    private void registerCreateExpectation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "HTTP method (GET, POST, PUT, DELETE, etc.)");
        properties.putObject("path").put("type", "string").put("description", "Request path to match");
        properties.putObject("statusCode").put("type", "integer").put("description", "Response status code (default 200)");
        ObjectNode responseBodyProp = properties.putObject("responseBody");
        responseBodyProp.put("description", "Response body as string or object");
        ArrayNode anyOf = responseBodyProp.putArray("anyOf");
        anyOf.add(objectMapper.createObjectNode().put("type", "string"));
        anyOf.add(objectMapper.createObjectNode().put("type", "object"));
        properties.putObject("responseHeaders").put("type", "object").put("description", "Response headers as key-value pairs");
        properties.putObject("times").put("type", "integer").put("description", "Number of times this expectation should match");
        properties.putObject("timeToLive").put("type", "string").put("description", "Time to live e.g. '60 SECONDS'");
        ArrayNode required = schema.putArray("required");
        required.add("method");
        required.add("path");

        tools.put("create_expectation", new ToolDefinition(
            "create_expectation",
            "Creates a mock expectation that defines how MockServer should respond to a matching request",
            schema,
            this::handleCreateExpectation
        ));
    }

    private JsonNode handleCreateExpectation(JsonNode params) {
        try {
            String method = params.path("method").asText(null);
            if (method == null || method.trim().isEmpty()) {
                return errorResult("'method' is required and must not be blank");
            }
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            JsonNode statusCodeNode = params.path("statusCode");
            int statusCode = 200;
            if (!statusCodeNode.isMissingNode() && !statusCodeNode.isNull()) {
                if (!statusCodeNode.isIntegralNumber()) {
                    return errorResult("'statusCode' must be an integer");
                }
                statusCode = statusCodeNode.asInt();
                if (statusCode < 100 || statusCode > 999) {
                    return errorResult("'statusCode' must be between 100 and 999");
                }
            }

            HttpRequest httpRequest = request().withMethod(method).withPath(path);
            HttpResponse httpResponse = response().withStatusCode(statusCode);

            JsonNode responseBody = params.path("responseBody");
            if (!responseBody.isMissingNode() && !responseBody.isNull()) {
                if (responseBody.isTextual()) {
                    httpResponse.withBody(responseBody.asText());
                } else {
                    httpResponse.withBody(objectMapper.writeValueAsString(responseBody), MediaType.APPLICATION_JSON);
                }
            }

            JsonNode responseHeaders = params.path("responseHeaders");
            if (responseHeaders.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = responseHeaders.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    httpResponse.withHeader(entry.getKey(), entry.getValue().asText());
                }
            }

            Expectation expectation = Expectation.when(httpRequest).thenRespond(httpResponse);

            JsonNode timesNode = params.path("times");
            if (!timesNode.isMissingNode() && !timesNode.isNull()) {
                if (!timesNode.isIntegralNumber()) {
                    return errorResult("'times' must be an integer");
                }
                int timesValue = timesNode.asInt();
                if (timesValue < 0) {
                    return errorResult("'times' must be a non-negative integer");
                }
                expectation = Expectation.when(httpRequest, Times.exactly(timesValue), TimeToLive.unlimited()).thenRespond(httpResponse);
            }

            JsonNode ttlNode = params.path("timeToLive");
            if (!ttlNode.isMissingNode() && !ttlNode.isNull() && !ttlNode.isTextual()) {
                return errorResult("'timeToLive' must be a string in format '<number> <UNIT>' (e.g., '60 SECONDS')");
            }
            if (!ttlNode.isMissingNode() && !ttlNode.isNull() && ttlNode.isTextual()) {
                String ttlStr = ttlNode.asText().trim();
                String[] parts = ttlStr.split("\\s+", 2);
                if (parts.length != 2) {
                    return errorResult("'timeToLive' must be in format '<number> <UNIT>' (e.g., '60 SECONDS')");
                }
                long ttlValue;
                try {
                    ttlValue = Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    return errorResult("'timeToLive' value must be a number");
                }
                if (ttlValue <= 0) {
                    return errorResult("'timeToLive' value must be positive");
                }
                TimeUnit timeUnit;
                try {
                    timeUnit = TimeUnit.valueOf(parts[1].toUpperCase());
                } catch (IllegalArgumentException e) {
                    return errorResult("'timeToLive' unit must be one of: DAYS, HOURS, MINUTES, SECONDS, MILLISECONDS, MICROSECONDS, NANOSECONDS");
                }
                Times effectiveTimes = (timesNode.isMissingNode() || timesNode.isNull()) ? Times.unlimited() : Times.exactly(timesNode.asInt());
                expectation = Expectation.when(httpRequest, effectiveTimes, TimeToLive.exactly(timeUnit, ttlValue)).thenRespond(httpResponse);
            }

            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create expectation", e);
        }
    }

    private void registerVerifyRequest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "HTTP method to verify");
        properties.putObject("path").put("type", "string").put("description", "Request path to verify");
        properties.putObject("atLeast").put("type", "integer").put("description", "Minimum number of matching requests expected");
        properties.putObject("atMost").put("type", "integer").put("description", "Maximum number of matching requests expected");

        tools.put("verify_request", new ToolDefinition(
            "verify_request",
            "Verifies that a request matching the specified criteria was received a certain number of times",
            schema,
            this::handleVerifyRequest
        ));
    }

    private JsonNode handleVerifyRequest(JsonNode params) {
        try {
            HttpRequest httpRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                httpRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                httpRequest.withPath(pathNode.asText());
            }

            JsonNode atLeastNode = params.path("atLeast");
            if (!atLeastNode.isMissingNode() && !atLeastNode.isNull() && !atLeastNode.isIntegralNumber()) {
                return errorResult("'atLeast' must be an integer");
            }
            JsonNode atMostNode = params.path("atMost");
            if (!atMostNode.isMissingNode() && !atMostNode.isNull() && !atMostNode.isIntegralNumber()) {
                return errorResult("'atMost' must be an integer");
            }
            int atLeast = atLeastNode.asInt(1);
            int atMost = atMostNode.asInt(-1);
            if (atLeast < 0) {
                return errorResult("'atLeast' must be non-negative");
            }
            if (atMost != -1 && atMost < atLeast) {
                return errorResult("'atMost' must be >= 'atLeast'");
            }

            VerificationTimes times;
            if (atMost == -1) {
                times = VerificationTimes.atLeast(atLeast);
            } else {
                times = VerificationTimes.between(atLeast, atMost);
            }

            Verification verification = new Verification()
                .withRequest(httpRequest)
                .withTimes(times);

            Future<String> result = httpState.verify(verification);
            String verificationResult = result.get(10, TimeUnit.SECONDS);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (verificationResult == null || verificationResult.isEmpty()) {
                resultNode.put("verified", true);
                resultNode.put("message", "Verification passed");
            } else {
                resultNode.put("verified", false);
                resultNode.put("message", verificationResult);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Verification failed", e);
        }
    }

    private void registerRetrieveRecordedRequests() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter by HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Filter by request path");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of requests to return (default 50)");

        tools.put("retrieve_recorded_requests", new ToolDefinition(
            "retrieve_recorded_requests",
            "Retrieves recorded requests that were received by MockServer, optionally filtered by method and path",
            schema,
            this::handleRetrieveRecordedRequests
        ));
    }

    private JsonNode handleRetrieveRecordedRequests(JsonNode params) {
        try {
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }
            int limit = params.path("limit").asInt(50);
            if (limit < 1 || limit > 500) {
                return errorResult("'limit' must be between 1 and 500");
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "REQUESTS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body != null && !body.isEmpty()) {
                JsonNode allRequests = objectMapper.readTree(body);
                if (allRequests.isArray() && allRequests.size() > limit) {
                    ArrayNode limited = objectMapper.createArrayNode();
                    for (int i = allRequests.size() - limit; i < allRequests.size(); i++) {
                        limited.add(allRequests.get(i));
                    }
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requests", limited);
                    resultNode.put("total", allRequests.size());
                    resultNode.put("returned", limited.size());
                    return resultNode;
                } else {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requests", allRequests);
                    resultNode.put("total", allRequests.isArray() ? allRequests.size() : 0);
                    resultNode.put("returned", allRequests.isArray() ? allRequests.size() : 0);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("requests", objectMapper.createArrayNode());
            resultNode.put("total", 0);
            resultNode.put("returned", 0);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve requests", e);
        }
    }

    private void registerClearExpectations() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter by HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Filter by request path");
        ObjectNode typeProp = properties.putObject("type");
        typeProp.put("type", "string").put("description", "What to clear: ALL, LOG, or EXPECTATIONS");
        ArrayNode typeEnum = typeProp.putArray("enum");
        typeEnum.add("ALL");
        typeEnum.add("LOG");
        typeEnum.add("EXPECTATIONS");

        tools.put("clear_expectations", new ToolDefinition(
            "clear_expectations",
            "Clears expectations and/or recorded requests from MockServer matching the specified criteria",
            schema,
            this::handleClearExpectations
        ));
    }

    private JsonNode handleClearExpectations(JsonNode params) {
        try {
            HttpRequest clearRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/clear");

            String type = params.path("type").asText("ALL");
            if (!"ALL".equals(type) && !"LOG".equals(type) && !"EXPECTATIONS".equals(type)) {
                return errorResult("'type' must be one of: ALL, LOG, EXPECTATIONS");
            }
            clearRequest.withQueryStringParameter("type", type);

            JsonNode methodNode = params.path("method");
            JsonNode pathNode = params.path("path");
            boolean hasFilter = (!methodNode.isMissingNode() && !methodNode.isNull()) ||
                (!pathNode.isMissingNode() && !pathNode.isNull());

            if (hasFilter) {
                HttpRequest filterRequest = request();
                if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                    filterRequest.withMethod(methodNode.asText());
                }
                if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                    filterRequest.withPath(pathNode.asText());
                }
                clearRequest.withBody(getRequestDefinitionSerializer().serialize(filterRequest));
            }

            httpState.clear(clearRequest);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "cleared");
            resultNode.put("type", type);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to clear", e);
        }
    }

    private void registerReset() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("reset", new ToolDefinition(
            "reset",
            "Resets MockServer by clearing all expectations, recorded requests, and logs",
            schema,
            this::handleReset
        ));
    }

    private JsonNode handleReset(JsonNode params) {
        try {
            httpState.reset();
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "reset");
            resultNode.put("message", "MockServer has been reset");
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to reset", e);
        }
    }

    private void registerGetStatus() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("get_status", new ToolDefinition(
            "get_status",
            "Returns the current status of MockServer including listening ports and running state",
            schema,
            this::handleGetStatus
        ));
    }

    private JsonNode handleGetStatus(JsonNode params) {
        try {
            ObjectNode resultNode = objectMapper.createObjectNode();
            List<Integer> ports = server.getLocalPorts();
            ArrayNode portsArray = resultNode.putArray("ports");
            for (Integer port : ports) {
                portsArray.add(port);
            }
            resultNode.put("running", server.isRunning());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to get status", e);
        }
    }

    private void registerVerifyRequestSequence() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode requestsProp = properties.putObject("requests");
        requestsProp.put("type", "array").put("description", "Ordered list of requests to verify in sequence");
        ObjectNode requestItems = requestsProp.putObject("items");
        requestItems.put("type", "object");
        ObjectNode itemProps = requestItems.putObject("properties");
        itemProps.putObject("method").put("type", "string");
        itemProps.putObject("path").put("type", "string");
        schema.putArray("required").add("requests");

        tools.put("verify_request_sequence", new ToolDefinition(
            "verify_request_sequence",
            "Verifies that requests were received in the specified order",
            schema,
            this::handleVerifyRequestSequence
        ));
    }

    private JsonNode handleVerifyRequestSequence(JsonNode params) {
        try {
            JsonNode requestsNode = params.path("requests");
            if (!requestsNode.isArray() || requestsNode.size() == 0) {
                return errorResult("'requests' must be a non-empty array");
            }

            VerificationSequence sequence = new VerificationSequence();
            List<org.mockserver.model.RequestDefinition> requests = new ArrayList<>();
            for (JsonNode reqNode : requestsNode) {
                if (!reqNode.isObject()) {
                    return errorResult("Each element of 'requests' must be an object");
                }
                HttpRequest httpRequest = request();
                JsonNode methodNode = reqNode.path("method");
                if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                    httpRequest.withMethod(methodNode.asText());
                }
                JsonNode pathNode = reqNode.path("path");
                if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                    httpRequest.withPath(pathNode.asText());
                }
                requests.add(httpRequest);
            }
            sequence.withRequests(requests);

            Future<String> result = httpState.verify(sequence);
            String verificationResult = result.get(10, TimeUnit.SECONDS);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (verificationResult == null || verificationResult.isEmpty()) {
                resultNode.put("verified", true);
                resultNode.put("message", "Request sequence verification passed");
            } else {
                resultNode.put("verified", false);
                resultNode.put("message", verificationResult);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Sequence verification failed", e);
        }
    }

    private void registerRetrieveRequestResponses() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter by HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Filter by request path");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of pairs to return (default 50)");

        tools.put("retrieve_request_responses", new ToolDefinition(
            "retrieve_request_responses",
            "Retrieves request-response pairs that were recorded by MockServer",
            schema,
            this::handleRetrieveRequestResponses
        ));
    }

    private JsonNode handleRetrieveRequestResponses(JsonNode params) {
        try {
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }
            int limit = params.path("limit").asInt(50);
            if (limit < 1 || limit > 500) {
                return errorResult("'limit' must be between 1 and 500");
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "REQUEST_RESPONSES")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body != null && !body.isEmpty()) {
                JsonNode allPairs = objectMapper.readTree(body);
                if (allPairs.isArray() && allPairs.size() > limit) {
                    ArrayNode limited = objectMapper.createArrayNode();
                    for (int i = allPairs.size() - limit; i < allPairs.size(); i++) {
                        limited.add(allPairs.get(i));
                    }
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requestResponses", limited);
                    resultNode.put("total", allPairs.size());
                    resultNode.put("returned", limited.size());
                    return resultNode;
                } else {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requestResponses", allPairs);
                    resultNode.put("total", allPairs.isArray() ? allPairs.size() : 0);
                    resultNode.put("returned", allPairs.isArray() ? allPairs.size() : 0);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("requestResponses", objectMapper.createArrayNode());
            resultNode.put("total", 0);
            resultNode.put("returned", 0);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve request-response pairs", e);
        }
    }

    private void registerCreateForwardExpectation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description", "Request path to match for forwarding");
        properties.putObject("host").put("type", "string").put("description", "Host to forward requests to");
        properties.putObject("port").put("type", "integer").put("description", "Port to forward requests to (default 443)");
        ObjectNode schemeProp = properties.putObject("scheme");
        schemeProp.put("type", "string").put("description", "Scheme for forwarding: HTTP or HTTPS (default HTTPS)");
        ArrayNode schemeEnum = schemeProp.putArray("enum");
        schemeEnum.add("HTTP");
        schemeEnum.add("HTTPS");
        ArrayNode required = schema.putArray("required");
        required.add("path");
        required.add("host");

        tools.put("create_forward_expectation", new ToolDefinition(
            "create_forward_expectation",
            "Creates a forward/proxy expectation that forwards matching requests to a specified host",
            schema,
            this::handleCreateForwardExpectation
        ));
    }

    private JsonNode handleCreateForwardExpectation(JsonNode params) {
        try {
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }
            String host = params.path("host").asText(null);
            if (host == null || host.trim().isEmpty()) {
                return errorResult("'host' is required and must not be blank");
            }
            JsonNode portNode = params.path("port");
            int port = 443;
            if (!portNode.isMissingNode() && !portNode.isNull()) {
                if (!portNode.isIntegralNumber()) {
                    return errorResult("'port' must be an integer");
                }
                port = portNode.asInt();
                if (port < 1 || port > 65535) {
                    return errorResult("'port' must be between 1 and 65535");
                }
            }
            String scheme = params.path("scheme").asText("HTTPS");
            if (!"HTTP".equals(scheme) && !"HTTPS".equals(scheme)) {
                return errorResult("'scheme' must be HTTP or HTTPS");
            }

            HttpRequest httpRequest = request().withPath(path);
            HttpForward httpForward = HttpForward.forward()
                .withHost(host)
                .withPort(port)
                .withScheme(HttpForward.Scheme.valueOf(scheme.toUpperCase()));

            Expectation expectation = Expectation.when(httpRequest).thenForward(httpForward);
            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            resultNode.put("forwardHost", host);
            resultNode.put("forwardPort", port);
            resultNode.put("forwardScheme", scheme);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create forward expectation", e);
        }
    }

    private void registerDebugRequestMismatch() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "HTTP method of the request to debug");
        properties.putObject("path").put("type", "string").put("description", "Path of the request to debug");
        properties.putObject("headers").put("type", "object").put("description", "Headers of the request to debug");
        ObjectNode bodyProp = properties.putObject("body");
        bodyProp.put("description", "Body of the request to debug");
        ArrayNode required = schema.putArray("required");
        required.add("method");
        required.add("path");

        tools.put("debug_request_mismatch", new ToolDefinition(
            "debug_request_mismatch",
            "Analyzes why a request does not match any active expectations, showing per-field match failures for each expectation",
            schema,
            this::handleDebugRequestMismatch
        ));
    }

    private JsonNode handleDebugRequestMismatch(JsonNode params) {
        try {
            HttpRequest httpRequest = request()
                .withMethod(params.path("method").asText())
                .withPath(params.path("path").asText());

            JsonNode headersNode = params.path("headers");
            if (headersNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    httpRequest.withHeader(entry.getKey(), entry.getValue().asText());
                }
            }

            JsonNode bodyNode = params.path("body");
            if (!bodyNode.isMissingNode() && !bodyNode.isNull()) {
                if (bodyNode.isTextual()) {
                    httpRequest.withBody(bodyNode.asText());
                } else {
                    httpRequest.withBody(objectMapper.writeValueAsString(bodyNode));
                }
            }

            HttpRequest wrapperRequest = request()
                .withMethod("PUT")
                .withBody(getRequestDefinitionSerializer().serialize(httpRequest));
            HttpResponse response = httpState.debugMismatch(wrapperRequest);
            JsonNode resultNode = objectMapper.readTree(response.getBodyAsString());

            // enhance: sort results by closeness (fewest differing fields first) and add remediation hints
            if (resultNode.has("results") && resultNode.get("results").isArray()) {
                ArrayNode results = (ArrayNode) resultNode.get("results");
                List<JsonNode> sortedResults = new ArrayList<>();
                for (JsonNode node : results) {
                    sortedResults.add(node);
                }
                sortedResults.sort((a, b) -> {
                    int aDiff = a.path("totalFieldCount").asInt(0) - a.path("matchedFieldCount").asInt(0);
                    int bDiff = b.path("totalFieldCount").asInt(0) - b.path("matchedFieldCount").asInt(0);
                    return Integer.compare(aDiff, bDiff);
                });

                ArrayNode rankedResults = objectMapper.createArrayNode();
                for (JsonNode node : sortedResults) {
                    // add remediation hints to each result that has differences
                    if (node.has("differences") && node.get("differences").isObject()) {
                        ObjectNode resultWithRemediation = (ObjectNode) node;
                        ObjectNode remediationNode = objectMapper.createObjectNode();
                        Iterator<Map.Entry<String, JsonNode>> diffFields = node.get("differences").fields();
                        while (diffFields.hasNext()) {
                            Map.Entry<String, JsonNode> diffField = diffFields.next();
                            MatchDifference.Field field = fieldFromName(diffField.getKey());
                            if (field != null && diffField.getValue().isArray()) {
                                List<String> diffs = new ArrayList<>();
                                for (JsonNode d : diffField.getValue()) {
                                    diffs.add(d.asText());
                                }
                                String hint = MismatchRemediation.hint(field, diffs);
                                if (!hint.isEmpty()) {
                                    remediationNode.put(diffField.getKey(), hint);
                                }
                            }
                        }
                        if (remediationNode.size() > 0) {
                            resultWithRemediation.set("remediation", remediationNode);
                        }
                    }
                    rankedResults.add(node);
                }
                ((ObjectNode) resultNode).set("results", rankedResults);

                // add remediation hint to closestMatch if present and is a JSON object
                if (resultNode.has("closestMatch") && resultNode.get("closestMatch").isObject() && rankedResults.size() > 0) {
                    JsonNode closest = rankedResults.get(0);
                    if (closest.has("remediation")) {
                        ((ObjectNode) resultNode.get("closestMatch")).set("remediation", closest.get("remediation"));
                    }
                }
            }

            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to debug request mismatch", e);
        }
    }

    private static MatchDifference.Field fieldFromName(String name) {
        for (MatchDifference.Field field : MatchDifference.Field.values()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private void registerExplainUnmatchedRequests() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of unmatched requests to return (default 10, max 100)");

        tools.put("explain_unmatched_requests", new ToolDefinition(
            "explain_unmatched_requests",
            "Returns recent requests that hit MockServer and matched no expectation, with ranked closest-expectation diagnostics and actionable remediation hints for each mismatch. Use after a failed test run to understand why requests got 404s without needing to reconstruct the request.",
            schema,
            this::handleExplainUnmatchedRequests
        ));
    }

    private JsonNode handleExplainUnmatchedRequests(JsonNode params) {
        try {
            int limit = params.path("limit").asInt(10);
            if (limit < 1 || limit > 100) {
                return errorResult("'limit' must be between 1 and 100");
            }

            HttpRequest wrapperRequest = request()
                .withMethod("PUT")
                .withBody("{\"limit\":" + limit + "}");
            HttpResponse response = httpState.explainUnmatched(wrapperRequest);
            return objectMapper.readTree(response.getBodyAsString());
        } catch (Exception e) {
            return errorResult("Failed to explain unmatched requests", e);
        }
    }

    private void registerCreateExpectationFromOpenApi() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec URL or JSON/YAML payload");
        properties.putObject("operationsAndResponses").put("type", "object").put("description", "Map of operationId to response status code (string) or object with statusCode and optional exampleName");
        properties.putObject("contextPathPrefix").put("type", "string").put("description", "Optional path prefix to prepend to all OpenAPI spec paths");
        schema.putArray("required").add("specUrlOrPayload");

        tools.put("create_expectation_from_openapi", new ToolDefinition(
            "create_expectation_from_openapi",
            "Creates expectations from an OpenAPI specification, optionally specifying which operations and response codes to use",
            schema,
            this::handleCreateExpectationFromOpenApi
        ));
    }

    private JsonNode handleCreateExpectationFromOpenApi(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText();
            OpenAPIExpectation openAPIExpectation = OpenAPIExpectation.openAPIExpectation(specUrlOrPayload);

            JsonNode opsNode = params.path("operationsAndResponses");
            if (opsNode.isObject()) {
                Map<String, Object> operationsAndResponses = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = opsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue().isTextual()) {
                        operationsAndResponses.put(entry.getKey(), entry.getValue().asText());
                    } else if (entry.getValue().isObject()) {
                        Map<String, Object> richValue = new LinkedHashMap<>();
                        JsonNode statusCode = entry.getValue().path("statusCode");
                        if (!statusCode.isMissingNode()) {
                            richValue.put("statusCode", statusCode.asText());
                        }
                        JsonNode exampleNameNode = entry.getValue().path("exampleName");
                        if (!exampleNameNode.isMissingNode()) {
                            richValue.put("exampleName", exampleNameNode.asText());
                        }
                        operationsAndResponses.put(entry.getKey(), richValue);
                    }
                }
                openAPIExpectation.withOperationsAndResponses(operationsAndResponses);
            }

            JsonNode contextPathNode = params.path("contextPathPrefix");
            if (contextPathNode.isTextual()) {
                openAPIExpectation.withContextPathPrefix(contextPathNode.asText());
            }

            List<Expectation> result = httpState.add(openAPIExpectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : result) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create expectations from OpenAPI", e);
        }
    }

    private void registerCreateExpectationsFromRecordedTraffic() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter recorded traffic by HTTP method (e.g. GET, POST)");
        properties.putObject("path").put("type", "string").put("description", "Filter recorded traffic by request path (e.g. /api/users)");
        properties.putObject("preview").put("type", "boolean").put("description", "When true, return the generated expectations as JSON without adding them. Defaults to false.");

        tools.put("create_expectations_from_recorded_traffic", new ToolDefinition(
            "create_expectations_from_recorded_traffic",
            "Converts traffic already recorded by MockServer's forwarding/proxy mode into active mock expectations in one step. "
                + "After observing a real API via a forwarding proxy, call this tool to generate expectations that replay the recorded responses. "
                + "Use preview=true to inspect the expectations before committing, or leave it false (default) to activate them immediately.",
            schema,
            this::handleCreateExpectationsFromRecordedTraffic
        ));
    }

    private JsonNode handleCreateExpectationsFromRecordedTraffic(JsonNode params) {
        try {
            // Build a filter request to retrieve RECORDED_EXPECTATIONS
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }

            boolean preview = params.path("preview").asBoolean(false);

            // Use the existing retrieve mechanism with RECORDED_EXPECTATIONS type
            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "RECORDED_EXPECTATIONS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body == null || body.isEmpty() || "[]".equals(body.trim())) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter. "
                    + "Ensure MockServer has forwarded requests (e.g. via create_forward_expectation) before calling this tool.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Deserialize the recorded expectations
            Expectation[] recordedExpectations = getExpectationSerializer().deserializeArray(body, false);

            if (recordedExpectations.length == 0) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter.");
                resultNode.put("count", 0);
                return resultNode;
            }

            if (preview) {
                // Return the expectations as JSON without adding them
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "preview");
                resultNode.put("count", recordedExpectations.length);
                resultNode.put("message", "Preview of expectations that would be created. Call again with preview=false to activate them.");
                ArrayNode expectationsArray = resultNode.putArray("expectations");
                for (Expectation exp : recordedExpectations) {
                    JsonNode expNode = objectMapper.readTree(getExpectationSerializer().serialize(exp));
                    expectationsArray.add(expNode);
                }
                return resultNode;
            } else {
                // Make expectations persistent: use unlimited times and TTL
                List<Expectation> addedExpectations = new ArrayList<>();
                for (Expectation recordedExp : recordedExpectations) {
                    Expectation persistentExp = new Expectation(
                        recordedExp.getHttpRequest(),
                        Times.unlimited(),
                        TimeToLive.unlimited(),
                        0
                    ).thenRespond(recordedExp.getHttpResponse());
                    addedExpectations.addAll(httpState.add(persistentExp));
                }

                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "created");
                resultNode.put("count", addedExpectations.size());
                ArrayNode ids = resultNode.putArray("ids");
                for (Expectation exp : addedExpectations) {
                    ids.add(exp.getId());
                }
                return resultNode;
            }
        } catch (Exception e) {
            return errorResult("Failed to create expectations from recorded traffic", e);
        }
    }

    private void registerStopServer() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("stop_server", new ToolDefinition(
            "stop_server",
            "Stops the MockServer instance",
            schema,
            this::handleStopServer
        ));
    }

    private JsonNode handleStopServer(JsonNode params) {
        try {
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "stopping");
            resultNode.put("message", "MockServer is shutting down");

            new Scheduler.SchedulerThreadFactory("MockServer MCP Stop").newThread(() -> server.stop()).start();

            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to stop server", e);
        }
    }

    private void registerRawExpectation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expectation").put("type", "object").put("description", "Full expectation JSON in MockServer format");
        schema.putArray("required").add("expectation");

        tools.put("raw_expectation", new ToolDefinition(
            "raw_expectation",
            "Creates an expectation using the full MockServer JSON format, supporting all features including templates, callbacks, and overrides",
            schema,
            this::handleRawExpectation
        ));
    }

    private JsonNode handleRawExpectation(JsonNode params) {
        try {
            JsonNode expectationNode = params.path("expectation");
            String expectationJson = objectMapper.writeValueAsString(expectationNode);

            Expectation[] expectations = getExpectationSerializer().deserializeArray(expectationJson, false);
            List<Expectation> allResults = new ArrayList<>();
            for (Expectation exp : expectations) {
                allResults.addAll(httpState.add(exp));
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", allResults.size());
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : allResults) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create raw expectation", e);
        }
    }

    private void registerRawRetrieve() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("requestDefinition").put("type", "object").put("description", "Request definition to filter results");
        ObjectNode typeProp = properties.putObject("type");
        typeProp.put("type", "string").put("description", "Type of data to retrieve");
        ArrayNode typeEnum = typeProp.putArray("enum");
        typeEnum.add("REQUESTS");
        typeEnum.add("REQUEST_RESPONSES");
        typeEnum.add("RECORDED_EXPECTATIONS");
        typeEnum.add("ACTIVE_EXPECTATIONS");
        typeEnum.add("LOGS");
        ObjectNode formatProp = properties.putObject("format");
        formatProp.put("type", "string").put("description", "Response format");
        ArrayNode formatEnum = formatProp.putArray("enum");
        formatEnum.add("JSON");
        formatEnum.add("JAVA");
        formatEnum.add("LOG_ENTRIES");
        properties.putObject("correlationId").put("type", "string").put("description", "Filter log entries by correlation ID (only applies when type is LOGS)");

        tools.put("raw_retrieve", new ToolDefinition(
            "raw_retrieve",
            "Retrieves data from MockServer using the full retrieve API with complete control over type, format, and correlation ID filtering",
            schema,
            this::handleRawRetrieve
        ));
    }

    private JsonNode handleRawRetrieve(JsonNode params) {
        try {
            String type = params.path("type").asText("REQUESTS");
            if (!"REQUESTS".equals(type) && !"REQUEST_RESPONSES".equals(type) && !"RECORDED_EXPECTATIONS".equals(type) && !"ACTIVE_EXPECTATIONS".equals(type) && !"LOGS".equals(type)) {
                return errorResult("'type' must be one of: REQUESTS, REQUEST_RESPONSES, RECORDED_EXPECTATIONS, ACTIVE_EXPECTATIONS, LOGS");
            }
            String format = params.path("format").asText("JSON");
            if (!"JSON".equals(format) && !"JAVA".equals(format) && !"LOG_ENTRIES".equals(format)) {
                return errorResult("'format' must be one of: JSON, JAVA, LOG_ENTRIES");
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", type)
                .withQueryStringParameter("format", format);

            JsonNode correlationIdNode = params.path("correlationId");
            if (correlationIdNode.isTextual() && !correlationIdNode.asText().isEmpty()) {
                retrieveRequest.withQueryStringParameter("correlationId", correlationIdNode.asText());
            }

            JsonNode requestDefNode = params.path("requestDefinition");
            if (requestDefNode.isObject()) {
                retrieveRequest.withBody(objectMapper.writeValueAsString(requestDefNode));
            }

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body != null && !body.isEmpty()) {
                try {
                    JsonNode parsed = objectMapper.readTree(body);
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("data", parsed);
                    return resultNode;
                } catch (Exception e) {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.put("data", body);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("data", "");
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve", e);
        }
    }

    private void registerRawVerify() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("verification").put("type", "object").put("description", "Full verification JSON in MockServer format");
        schema.putArray("required").add("verification");

        tools.put("raw_verify", new ToolDefinition(
            "raw_verify",
            "Performs request verification using the full MockServer verification JSON format",
            schema,
            this::handleRawVerify
        ));
    }

    private JsonNode handleRawVerify(JsonNode params) {
        try {
            JsonNode verificationNode = params.path("verification");
            String verificationJson = objectMapper.writeValueAsString(verificationNode);

            org.mockserver.serialization.VerificationSerializer verificationSerializer =
                new org.mockserver.serialization.VerificationSerializer(mockServerLogger);
            Verification verification = verificationSerializer.deserialize(verificationJson);

            Future<String> result = httpState.verify(verification);
            String verificationResult = result.get(10, TimeUnit.SECONDS);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (verificationResult == null || verificationResult.isEmpty()) {
                resultNode.put("verified", true);
                resultNode.put("message", "Verification passed");
            } else {
                resultNode.put("verified", false);
                resultNode.put("message", verificationResult);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Raw verification failed", e);
        }
    }

    private void registerVerifyTrafficAgainstOpenApi() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec as a URL, file path, or inline JSON/YAML payload");
        properties.putObject("method").put("type", "string").put("description", "Filter recorded traffic by HTTP method (e.g. GET, POST)");
        properties.putObject("path").put("type", "string").put("description", "Filter recorded traffic by request path (e.g. /api/users)");
        schema.putArray("required").add("specUrlOrPayload");

        tools.put("verify_traffic_against_openapi", new ToolDefinition(
            "verify_traffic_against_openapi",
            "Verify the API calls already recorded by MockServer conform to an OpenAPI contract. "
                + "Pulls recorded request-response pairs from the event log, validates each against the spec, "
                + "and returns a per-pair conformance report with request and response validation errors.",
            schema,
            this::handleVerifyTrafficAgainstOpenApi
        ));
    }

    private JsonNode handleVerifyTrafficAgainstOpenApi(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText(null);
            if (specUrlOrPayload == null || specUrlOrPayload.trim().isEmpty()) {
                return errorResult("'specUrlOrPayload' is required and must not be blank");
            }

            // Retrieve recorded request-response pairs
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "REQUEST_RESPONSES")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body == null || body.isEmpty() || "[]".equals(body.trim())) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_traffic");
                resultNode.put("message", "No recorded request-response pairs found matching the filter.");
                resultNode.put("totalPairs", 0);
                resultNode.put("passed", 0);
                resultNode.put("failed", 0);
                return resultNode;
            }

            // Parse request-response pairs using the standard serializer to preserve
            // all fields (cookies, query parameters, multi-value headers, etc.)
            LogEventRequestAndResponseSerializer requestAndResponseSerializer =
                new LogEventRequestAndResponseSerializer(mockServerLogger);
            LogEventRequestAndResponse[] parsed = requestAndResponseSerializer.deserializeArray(body);
            List<Pair<HttpRequest, HttpResponse>> pairs = new ArrayList<>();
            for (LogEventRequestAndResponse entry : parsed) {
                pairs.add(Pair.of(entry.getHttpRequest(), entry.getHttpResponse()));
            }

            // Validate
            OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);
            List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(specUrlOrPayload, pairs);

            // Build response
            int passedCount = 0;
            int failedCount = 0;
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (OpenApiTrafficValidator.TrafficValidationResult result : results) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("method", result.getRequestMethod());
                resultNode.put("path", result.getRequestPath());
                if (result.getMatchedOperation() != null) {
                    resultNode.put("matchedOperation", result.getMatchedOperation());
                } else {
                    resultNode.putNull("matchedOperation");
                }
                resultNode.put("passed", result.isPassed());

                if (!result.getRequestErrors().isEmpty()) {
                    ArrayNode reqErrors = resultNode.putArray("requestErrors");
                    for (String error : result.getRequestErrors()) {
                        reqErrors.add(error);
                    }
                }
                if (!result.getResponseErrors().isEmpty()) {
                    ArrayNode respErrors = resultNode.putArray("responseErrors");
                    for (String error : result.getResponseErrors()) {
                        respErrors.add(error);
                    }
                }

                if (result.isPassed()) {
                    passedCount++;
                } else {
                    failedCount++;
                }
                resultsArray.add(resultNode);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", failedCount == 0 ? "conformant" : "non_conformant");
            responseNode.put("totalPairs", results.size());
            responseNode.put("passed", passedCount);
            responseNode.put("failed", failedCount);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to verify traffic against OpenAPI", e);
        }
    }

    private void registerRunContractTest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec as a URL, file path, or inline JSON/YAML payload");
        properties.putObject("baseUrl").put("type", "string").put("description", "Base URL of the service under test (e.g. http://localhost:8080)");
        properties.putObject("operationId").put("type", "string").put("description", "Optional filter to test only a specific operation by operationId");
        ArrayNode required = schema.putArray("required");
        required.add("specUrlOrPayload");
        required.add("baseUrl");

        tools.put("run_contract_test", new ToolDefinition(
            "run_contract_test",
            "Send example requests derived from an OpenAPI spec to a running service and check the responses conform — "
                + "use this to verify a service you just built or changed. Builds representative requests for each "
                + "operation (path parameters, query parameters, headers, request body) from spec examples and schema defaults, "
                + "sends them to the specified base URL, and validates each response against the spec. "
                + "Each request has a 10-second timeout.",
            schema,
            this::handleRunContractTest
        ));
    }

    private JsonNode handleRunContractTest(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText(null);
            if (specUrlOrPayload == null || specUrlOrPayload.trim().isEmpty()) {
                return errorResult("'specUrlOrPayload' is required and must not be blank");
            }
            String baseUrl = params.path("baseUrl").asText(null);
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                return errorResult("'baseUrl' is required and must not be blank");
            }

            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("MCP run_contract_test sending requests to external target:{}")
                    .setArguments(baseUrl)
            );

            String operationIdFilter = params.path("operationId").asText(null);

            // Parse baseUrl into host, port, scheme
            java.net.URI uri;
            try {
                uri = new java.net.URI(baseUrl);
            } catch (Exception e) {
                return errorResult("'baseUrl' is not a valid URL: " + baseUrl);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return errorResult("'baseUrl' must be an absolute HTTP/HTTPS URL with a hostname: " + baseUrl);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return errorResult("'baseUrl' must use the http or https scheme: " + baseUrl);
            }
            int port = uri.getPort();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            if (port == -1) {
                port = secure ? 443 : 80;
            }
            String basePath = uri.getPath() != null ? uri.getPath() : "";
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            final String effectiveBasePath = basePath;
            final java.net.InetSocketAddress remoteAddress = new java.net.InetSocketAddress(host, port);
            final boolean isSecure = secure;

            OpenApiContractTest contractTest = new OpenApiContractTest(mockServerLogger);
            List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
                specUrlOrPayload,
                baseUrl,
                operationIdFilter,
                request -> {
                    try {
                        // Prepend base path to request path
                        String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
                        if (!effectiveBasePath.isEmpty() && !requestPath.startsWith(effectiveBasePath)) {
                            request.withPath(effectiveBasePath + requestPath);
                        }
                        request.withHeader("Host", host + ":" + remoteAddress.getPort());
                        request.withSecure(isSecure);

                        // 10s timeout: contract tests expect a healthy endpoint that responds
                        // promptly; a longer timeout avoids false failures on slow cold-start services
                        return sendHttpRequest(request, remoteAddress, isSecure);
                    } catch (Exception e) {
                        return HttpResponse.response()
                            .withStatusCode(0)
                            .withBody("connection error: " + e.getMessage());
                    }
                }
            );

            // Build response
            int passedCount = 0;
            int failedCount = 0;
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (OpenApiContractTest.ContractTestResult result : results) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("operationId", result.getOperationId());
                resultNode.put("method", result.getMethod());
                resultNode.put("path", result.getPath());
                resultNode.put("statusCode", result.getStatusCodeReceived());
                resultNode.put("passed", result.isPassed());

                if (!result.getValidationErrors().isEmpty()) {
                    ArrayNode errors = resultNode.putArray("validationErrors");
                    for (String error : result.getValidationErrors()) {
                        errors.add(error);
                    }
                }

                if (result.isPassed()) {
                    passedCount++;
                } else {
                    failedCount++;
                }
                resultsArray.add(resultNode);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", failedCount == 0 ? "all_passed" : "failures_detected");
            responseNode.put("totalOperations", results.size());
            responseNode.put("passed", passedCount);
            responseNode.put("failed", failedCount);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to run contract test", e);
        }
    }

    private void registerRunResiliencyTest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec as a URL, file path, or inline JSON/YAML payload");
        properties.putObject("baseUrl").put("type", "string").put("description", "Base URL of the service under test (e.g. http://localhost:8080)");
        properties.putObject("operationId").put("type", "string").put("description", "Optional filter to test only a specific operation by operationId");
        ArrayNode required = schema.putArray("required");
        required.add("specUrlOrPayload");
        required.add("baseUrl");

        tools.put("run_resiliency_test", new ToolDefinition(
            "run_resiliency_test",
            "Send malformed and boundary-case requests derived from an OpenAPI spec to a running service "
                + "and report which inputs it failed to handle gracefully — use this to verify the error handling "
                + "of a service you built or changed. Generates mutations such as omitting required fields, "
                + "type violations, numeric and string boundary violations, and malformed JSON bodies. "
                + "Each request has a 5-second timeout so unresponsive endpoints are classified as UNEXPECTED promptly.",
            schema,
            this::handleRunResiliencyTest
        ));
    }

    private JsonNode handleRunResiliencyTest(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText(null);
            if (specUrlOrPayload == null || specUrlOrPayload.trim().isEmpty()) {
                return errorResult("'specUrlOrPayload' is required and must not be blank");
            }
            String baseUrl = params.path("baseUrl").asText(null);
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                return errorResult("'baseUrl' is required and must not be blank");
            }

            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("MCP run_resiliency_test sending requests to external target:{}")
                    .setArguments(baseUrl)
            );

            String operationIdFilter = params.path("operationId").asText(null);

            // Parse baseUrl into host, port, scheme
            java.net.URI uri;
            try {
                uri = new java.net.URI(baseUrl);
            } catch (Exception e) {
                return errorResult("'baseUrl' is not a valid URL: " + baseUrl);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return errorResult("'baseUrl' must be an absolute HTTP/HTTPS URL with a hostname: " + baseUrl);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return errorResult("'baseUrl' must use the http or https scheme: " + baseUrl);
            }
            int port = uri.getPort();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            if (port == -1) {
                port = secure ? 443 : 80;
            }
            String basePath = uri.getPath() != null ? uri.getPath() : "";
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            final String effectiveBasePath = basePath;
            final java.net.InetSocketAddress remoteAddress = new java.net.InetSocketAddress(host, port);
            final boolean isSecure = secure;

            OpenApiResiliencyTest resiliencyTest = new OpenApiResiliencyTest(mockServerLogger);
            OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
                specUrlOrPayload,
                baseUrl,
                operationIdFilter,
                request -> {
                    try {
                        String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
                        if (!effectiveBasePath.isEmpty() && !requestPath.startsWith(effectiveBasePath)) {
                            request.withPath(effectiveBasePath + requestPath);
                        }
                        request.withHeader("Host", host + ":" + remoteAddress.getPort());
                        request.withSecure(isSecure);
                        // 5s timeout: resiliency tests deliberately send malformed input, so an
                        // unresponsive endpoint should be classified as UNEXPECTED promptly
                        return sendHttpRequest(request, remoteAddress, isSecure, 5000);
                    } catch (Exception e) {
                        return HttpResponse.response()
                            .withStatusCode(0)
                            .withBody("connection error: " + e.getMessage());
                    }
                }
            );

            // Build response
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("operationId", result.getOperationId());
                resultNode.put("method", result.getMethod());
                resultNode.put("path", result.getPath());
                resultNode.put("mutationType", result.getMutationType().name());
                resultNode.put("mutationDescription", result.getMutationDescription());
                resultNode.put("statusCode", result.getStatusCode());
                resultNode.put("classification", result.getClassification().name());
                resultsArray.add(resultNode);
            }

            // Per-operation summaries
            ArrayNode operationSummaries = objectMapper.createArrayNode();
            for (OpenApiResiliencyTest.OperationSummary summary : report.getOperationSummaries().values()) {
                ObjectNode summaryNode = objectMapper.createObjectNode();
                summaryNode.put("operationId", summary.getOperationId());
                summaryNode.put("method", summary.getMethod());
                summaryNode.put("path", summary.getPath());
                summaryNode.put("handled", summary.getHandled());
                summaryNode.put("unexpected", summary.getUnexpected());
                operationSummaries.add(summaryNode);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", report.getUnexpectedCount() == 0 ? "all_handled" : "failures_detected");
            responseNode.put("totalMutations", report.getTotalMutations());
            responseNode.put("handled", report.getHandledCount());
            responseNode.put("unexpected", report.getUnexpectedCount());
            responseNode.set("operationSummaries", operationSummaries);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to run resiliency test", e);
        }
    }

    private void registerRunMcpContractTest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("targetUrl").put("type", "string").put("description", "Full URL of the target MCP server's Streamable HTTP endpoint (e.g. http://localhost:1080/mockserver/mcp)");
        properties.putObject("protocolVersion").put("type", "string").put("description", "MCP protocol version to advertise during initialize (default 2025-03-26)");
        properties.putObject("toolName").put("type", "string").put("description", "Optional tool to exercise via a tools/call shape check; omit to skip (a tools/call may have side effects on the target)");
        ArrayNode required = schema.putArray("required");
        required.add("targetUrl");

        tools.put("run_mcp_contract_test", new ToolDefinition(
            "run_mcp_contract_test",
            "Check that a target MCP (Model Context Protocol) server correctly implements the protocol over Streamable HTTP — "
                + "use this to verify an MCP server you built or changed. Runs the required JSON-RPC handshake and core methods against "
                + "the endpoint (initialize, notifications/initialized, ping, tools/list, and unknown-method rejection) and validates the "
                + "shape of each response (JSON-RPC 2.0 envelope, required result fields), never the semantics of any tool. Optionally "
                + "exercises one tools/call. Each request has a 10-second timeout (so up to ~60 seconds total across all checks).",
            schema,
            this::handleRunMcpContractTest
        ));
    }

    private JsonNode handleRunMcpContractTest(JsonNode params) {
        try {
            String targetUrl = params.path("targetUrl").asText(null);
            if (targetUrl == null || targetUrl.trim().isEmpty()) {
                return errorResult("'targetUrl' is required and must not be blank");
            }
            String protocolVersion = params.path("protocolVersion").asText(null);
            String toolName = params.path("toolName").asText(null);

            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("MCP run_mcp_contract_test connecting to external MCP server:{}")
                    .setArguments(targetUrl)
            );

            // Parse targetUrl into host, port, scheme, path
            java.net.URI uri;
            try {
                uri = new java.net.URI(targetUrl);
            } catch (Exception e) {
                return errorResult("'targetUrl' is not a valid URL: " + targetUrl);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return errorResult("'targetUrl' must be an absolute HTTP/HTTPS URL with a hostname: " + targetUrl);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return errorResult("'targetUrl' must use the http or https scheme: " + targetUrl);
            }
            final boolean isSecure = "https".equalsIgnoreCase(scheme);
            int port = uri.getPort();
            if (port == -1) {
                port = isSecure ? 443 : 80;
            }
            final java.net.InetSocketAddress remoteAddress = new java.net.InetSocketAddress(host, port);
            final String hostHeader = host + ":" + port;
            final String mcpPath = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();

            McpContractTest.JsonRpcExchange exchange = (message, sessionId) -> {
                try {
                    HttpRequest httpRequest = request()
                        .withMethod("POST")
                        .withPath(mcpPath)
                        .withHeader("Host", hostHeader)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Accept", "application/json, text/event-stream")
                        .withSecure(isSecure)
                        .withBody(objectMapper.writeValueAsString(message));
                    if (sessionId != null && !sessionId.isEmpty()) {
                        httpRequest.withHeader("Mcp-Session-Id", sessionId);
                    }
                    HttpResponse httpResponse = sendHttpRequest(httpRequest, remoteAddress, isSecure);
                    int statusCode = httpResponse.getStatusCode() != null ? httpResponse.getStatusCode() : 0;
                    if (statusCode == 0) {
                        return McpContractTest.ExchangeResult.transportError(httpResponse.getBodyAsString());
                    }
                    return new McpContractTest.ExchangeResult(
                        statusCode,
                        firstHeaderIgnoreCase(httpResponse, "Mcp-Session-Id"),
                        parseJsonRpcBody(httpResponse),
                        null
                    );
                } catch (Exception e) {
                    return McpContractTest.ExchangeResult.transportError(e.getMessage());
                }
            };

            McpContractTest.Report report = new McpContractTest(objectMapper).run(protocolVersion, toolName, exchange);

            int passed = 0;
            int failed = 0;
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (McpContractTest.CheckResult check : report.getChecks()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("check", check.getCheck());
                node.put("passed", check.isPassed());
                if (check.getStatusCode() != null) {
                    node.put("statusCode", check.getStatusCode());
                }
                if (check.getDetail() != null) {
                    node.put("detail", check.getDetail());
                }
                if (!check.getValidationErrors().isEmpty()) {
                    ArrayNode errors = node.putArray("validationErrors");
                    for (String error : check.getValidationErrors()) {
                        errors.add(error);
                    }
                }
                if (check.isPassed()) {
                    passed++;
                } else {
                    failed++;
                }
                resultsArray.add(node);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", failed == 0 ? "all_passed" : "failures_detected");
            responseNode.put("protocolVersion", report.getProtocolVersion());
            if (report.getServerName() != null) {
                ObjectNode serverInfo = responseNode.putObject("serverInfo");
                serverInfo.put("name", report.getServerName());
                if (report.getServerVersion() != null) {
                    serverInfo.put("version", report.getServerVersion());
                }
            }
            responseNode.put("totalChecks", report.getChecks().size());
            responseNode.put("passed", passed);
            responseNode.put("failed", failed);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to run MCP contract test", e);
        }
    }

    /**
     * Return the first value of the named response header, matched case-insensitively, or null.
     */
    private String firstHeaderIgnoreCase(HttpResponse response, String name) {
        if (response.getHeaderList() == null) {
            return null;
        }
        for (org.mockserver.model.Header header : response.getHeaderList()) {
            if (header.getName() != null && header.getName().getValue() != null
                && header.getName().getValue().equalsIgnoreCase(name)
                && header.getValues() != null && !header.getValues().isEmpty()) {
                return header.getValues().get(0).getValue();
            }
        }
        return null;
    }

    /**
     * Parse the JSON-RPC message from an MCP HTTP response. Streamable HTTP servers may reply with
     * either application/json or text/event-stream; for SSE the JSON-RPC message is carried in the
     * "data:" field(s). Returns null when the body is empty or not valid JSON.
     */
    private JsonNode parseJsonRpcBody(HttpResponse response) {
        String body = response.getBodyAsString();
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        String contentType = firstHeaderIgnoreCase(response, "Content-Type");
        String json = body;
        boolean looksLikeSse = (contentType != null && contentType.toLowerCase().contains("text/event-stream"))
            || body.stripLeading().startsWith("event:")
            || body.stripLeading().startsWith("data:");
        if (looksLikeSse) {
            // Extract the first SSE event's data only. Within an event, multiple "data:" lines
            // are joined with newlines (SSE semantics); a blank line ends the event. A synchronous
            // JSON-RPC response carries one message, so stop at the first complete event.
            StringBuilder data = new StringBuilder();
            boolean collecting = false;
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append("\n");
                    }
                    data.append(trimmed.substring("data:".length()).trim());
                    collecting = true;
                } else if (trimmed.isEmpty() && collecting) {
                    break;
                }
            }
            if (data.length() > 0) {
                json = data.toString();
            }
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Send an HTTP request using java.net.HttpURLConnection for simplicity and client-agnosticism.
     * This avoids the need to construct a NettyHttpClient which requires an EventLoopGroup.
     */
    private HttpResponse sendHttpRequest(HttpRequest request, java.net.InetSocketAddress remoteAddress, boolean secure) {
        return sendHttpRequest(request, remoteAddress, secure, 10000);
    }

    /**
     * Maximum response body size (10 MB). Responses exceeding this limit are truncated.
     */
    private static final int MAX_RESPONSE_BODY_SIZE = 10 * 1024 * 1024;

    /**
     * Maximum fixture file size (50 MB). Files exceeding this limit are rejected to prevent OOM.
     */
    static final long MAX_FIXTURE_FILE_SIZE = 50L * 1024 * 1024;

    private HttpResponse sendHttpRequest(HttpRequest request, java.net.InetSocketAddress remoteAddress, boolean secure, int timeoutMillis) {
        java.net.HttpURLConnection connection = null;
        try {
            String scheme = secure ? "https" : "http";
            String path = request.getPath() != null ? request.getPath().getValue() : "/";

            // Build query string from query parameters
            StringBuilder queryString = new StringBuilder();
            if (request.getQueryStringParameterList() != null) {
                for (org.mockserver.model.Parameter param : request.getQueryStringParameterList()) {
                    if (param.getName() != null && param.getValues() != null) {
                        for (org.mockserver.model.NottableString value : param.getValues()) {
                            if (queryString.length() > 0) {
                                queryString.append("&");
                            } else {
                                queryString.append("?");
                            }
                            queryString.append(java.net.URLEncoder.encode(param.getName().getValue(), "UTF-8"));
                            queryString.append("=");
                            queryString.append(java.net.URLEncoder.encode(value.getValue(), "UTF-8"));
                        }
                    }
                }
            }

            String urlString = scheme + "://" + remoteAddress.getHostName() + ":" + remoteAddress.getPort() + path + queryString;
            java.net.URL url = new java.net.URL(urlString);
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod(request.getMethod() != null ? request.getMethod().getValue() : "GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);

            // Set headers
            if (request.getHeaderList() != null) {
                for (org.mockserver.model.Header header : request.getHeaderList()) {
                    if (header.getName() != null && header.getValues() != null && !header.getValues().isEmpty()) {
                        connection.setRequestProperty(header.getName().getValue(), header.getValues().get(0).getValue());
                    }
                }
            }

            // Set body
            String bodyString = request.getBodyAsString();
            if (bodyString != null && !bodyString.isEmpty()) {
                connection.setDoOutput(true);
                try (java.io.OutputStream os = connection.getOutputStream()) {
                    os.write(bodyString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            int statusCode = connection.getResponseCode();
            HttpResponse response = HttpResponse.response().withStatusCode(statusCode);

            // Read response headers
            Map<String, java.util.List<String>> responseHeaders = connection.getHeaderFields();
            if (responseHeaders != null) {
                for (Map.Entry<String, java.util.List<String>> entry : responseHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        response.withHeader(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                }
            }

            // Read response body with size cap
            java.io.InputStream rawStream;
            try {
                rawStream = connection.getInputStream();
            } catch (java.io.IOException e) {
                rawStream = connection.getErrorStream();
            }
            if (rawStream != null) {
                try (java.io.InputStream inputStream = rawStream) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalRead = 0;
                    boolean truncated = false;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        long remaining = MAX_RESPONSE_BODY_SIZE - totalRead;
                        if (remaining <= 0) {
                            truncated = true;
                            break;
                        }
                        int toWrite = (int) Math.min(bytesRead, remaining);
                        baos.write(buffer, 0, toWrite);
                        totalRead += toWrite;
                        if (toWrite < bytesRead) {
                            truncated = true;
                            break;
                        }
                    }
                    String bodyContent = baos.toString("UTF-8");
                    if (truncated) {
                        bodyContent += "\n[TRUNCATED: response body exceeded " + MAX_RESPONSE_BODY_SIZE + " bytes]";
                    }
                    response.withBody(bodyContent);
                }
            }

            return response;
        } catch (Exception e) {
            return HttpResponse.response()
                .withStatusCode(0)
                .withBody("connection error: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void registerRecordLlmFixtures() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description",
            "File path to write the fixture JSON to. The directory must exist.");
        properties.putObject("host").put("type", "string").put("description",
            "Optional filter: only include recorded traffic whose request host matches this value");
        properties.putObject("requestPath").put("type", "string").put("description",
            "Optional filter: only include recorded traffic whose request path matches this value");
        ObjectNode redactBodyFieldsProp = properties.putObject("redactBodyFields");
        redactBodyFieldsProp.put("type", "array").put("description",
            "Optional JSON field names whose values are redacted from request/response bodies (in addition to sensitive headers and the mockserver.fixtureBodyRedactFields config)");
        redactBodyFieldsProp.putObject("items").put("type", "string");
        schema.putArray("required").add("path");

        tools.put("record_llm_fixtures", new ToolDefinition(
            "record_llm_fixtures",
            "Snapshots LLM/MCP traffic recorded through MockServer's forwarding proxy into a committable, secret-free "
                + "fixture file for deterministic replay. Converts recorded request-response pairs (including SSE streaming "
                + "responses from APIs like Anthropic Claude, OpenAI, etc.) into MockServer expectations with secrets "
                + "(Authorization, api-key, etc.) automatically redacted from headers. JSON body fields are redacted too "
                + "when named via redactBodyFields or the mockserver.fixtureBodyRedactFields config; otherwise bodies are "
                + "left intact, so review fixtures before committing if your app puts credentials in bodies. "
                + "The output file uses MockServer's standard expectation JSON format and can be loaded "
                + "with load_expectations_from_file or via initializationJsonPath.",
            schema,
            this::handleRecordLlmFixtures
        ));
    }

    private JsonNode handleRecordLlmFixtures(JsonNode params) {
        try {
            String filePath = params.path("path").asText(null);
            if (filePath == null || filePath.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Validate the file path is inside allowed directories
            Path outputPath = Paths.get(filePath).toAbsolutePath().normalize();
            ObjectNode pathError = validateFilePath(outputPath, objectMapper);
            if (pathError != null) {
                return pathError;
            }

            File parentDir = outputPath.getParent().toFile();
            if (!parentDir.exists()) {
                return errorResult("Directory does not exist: " + parentDir.getAbsolutePath());
            }

            // Build a filter request to retrieve RECORDED_EXPECTATIONS
            HttpRequest filterRequest = request();
            JsonNode hostNode = params.path("host");
            if (!hostNode.isMissingNode() && !hostNode.isNull() && hostNode.isTextual()) {
                filterRequest.withHeader("Host", hostNode.asText());
            }
            JsonNode requestPathNode = params.path("requestPath");
            if (!requestPathNode.isMissingNode() && !requestPathNode.isNull() && requestPathNode.isTextual()) {
                filterRequest.withPath(requestPathNode.asText());
            }

            // Retrieve recorded expectations
            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "RECORDED_EXPECTATIONS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body == null || body.isEmpty() || "[]".equals(body.trim())) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter. "
                    + "Ensure MockServer has forwarded requests (e.g. via create_forward_expectation or proxy mode) before calling this tool.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Deserialize the recorded expectations
            Expectation[] recordedExpectations = getExpectationSerializer().deserializeArray(body, false);

            if (recordedExpectations.length == 0) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Convert SSE-streamed responses to HttpSseResponse expectations
            SseAwareExpectationConverter sseConverter = new SseAwareExpectationConverter();
            Expectation[] sseAwareExpectations = sseConverter.convert(recordedExpectations);

            // Redact sensitive headers, plus any configured/requested JSON body fields
            List<String> bodyFields = new ArrayList<>(splitCsv(ConfigurationProperties.fixtureBodyRedactFields()));
            JsonNode redactBodyFieldsNode = params.path("redactBodyFields");
            if (redactBodyFieldsNode.isArray()) {
                for (JsonNode f : redactBodyFieldsNode) {
                    if (f.isTextual() && !f.asText().isEmpty()) {
                        bodyFields.add(f.asText());
                    }
                }
            }
            FixtureRedactor redactor = bodyFields.isEmpty()
                ? new FixtureRedactor()
                : new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), bodyFields);
            Expectation[] redactedExpectations = redactor.redact(sseAwareExpectations);

            // Serialize to the fixture file
            String json = getExpectationSerializer().serialize(redactedExpectations);
            Files.write(outputPath, json.getBytes(StandardCharsets.UTF_8));

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "written");
            resultNode.put("count", redactedExpectations.length);
            resultNode.put("file", outputPath.toString());
            resultNode.put("message", "Wrote " + redactedExpectations.length + " expectation(s) to " + outputPath
                + ". Secrets have been redacted. Load with load_expectations_from_file or initializationJsonPath for replay.");
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to record LLM fixtures", e);
        }
    }

    private void registerLoadExpectationsFromFile() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description",
            "File path to the fixture JSON file to load. Must be a valid MockServer expectations JSON file.");
        properties.putObject("strict").put("type", "boolean").put("description",
            "Strict VCR mode: register a low-priority catch-all per cassette path so a request matching no recorded entry returns HTTP 599 instead of falling through. Defaults to the mockserver.llmVcrStrict config.");
        ObjectNode normalizeFieldsProp = properties.putObject("normalizeRequestBodyFields");
        normalizeFieldsProp.put("type", "array").put("description",
            "Optional JSON field names to drop from each recorded request body on load, matching the remaining fields loosely (ignoring extra fields), so volatile values do not block replay.");
        normalizeFieldsProp.putObject("items").put("type", "string");
        schema.putArray("required").add("path");

        tools.put("load_expectations_from_file", new ToolDefinition(
            "load_expectations_from_file",
            "Loads expectations from a JSON fixture file and adds them as active mock expectations in MockServer. "
                + "Use this to replay LLM/MCP traffic previously recorded with record_llm_fixtures. "
                + "The file must contain expectations in MockServer's standard JSON format (an array of expectation objects). "
                + "SSE streaming responses in the fixture are replayed with Server-Sent Events.",
            schema,
            this::handleLoadExpectationsFromFile
        ));
    }

    private JsonNode handleLoadExpectationsFromFile(JsonNode params) {
        try {
            String filePath = params.path("path").asText(null);
            if (filePath == null || filePath.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            Path inputPath = Paths.get(filePath).toAbsolutePath().normalize();
            ObjectNode pathError = validateFilePath(inputPath, objectMapper);
            if (pathError != null) {
                return pathError;
            }

            if (!Files.exists(inputPath)) {
                return errorResult("File does not exist: " + inputPath);
            }
            if (!Files.isReadable(inputPath)) {
                return errorResult("File is not readable: " + inputPath);
            }

            long fileSize = Files.size(inputPath);
            if (fileSize > MAX_FIXTURE_FILE_SIZE) {
                return errorResult("File exceeds the maximum allowed size of "
                    + (MAX_FIXTURE_FILE_SIZE / (1024 * 1024)) + " MB: " + inputPath);
            }

            String json = new String(Files.readAllBytes(inputPath), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                return errorResult("File is empty: " + inputPath);
            }

            Expectation[] expectations = getExpectationSerializer().deserializeArray(json, false);

            if (expectations.length == 0) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "empty");
                resultNode.put("message", "File contained no expectations.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Optional replay normalisation: drop volatile JSON fields from each
            // recorded request body and match the rest loosely (ignore extra fields).
            List<String> normalizeFields = new ArrayList<>();
            JsonNode normalizeFieldsNode = params.path("normalizeRequestBodyFields");
            if (normalizeFieldsNode.isArray()) {
                for (JsonNode f : normalizeFieldsNode) {
                    if (f.isTextual() && !f.asText().isEmpty()) {
                        normalizeFields.add(f.asText());
                    }
                }
            }
            if (!normalizeFields.isEmpty()) {
                for (Expectation exp : expectations) {
                    relaxRequestBodyForReplay(exp, normalizeFields);
                }
            }

            // Add expectations with unlimited times and TTL for replay
            List<Expectation> addedExpectations = new ArrayList<>();
            for (Expectation exp : expectations) {
                addedExpectations.addAll(httpState.add(exp));
            }

            // Strict VCR mode: register a low-priority catch-all per distinct path so a
            // request matching no recorded entry fails loudly rather than falling through.
            boolean strict = params.path("strict").isBoolean()
                ? params.path("strict").asBoolean()
                : ConfigurationProperties.llmVcrStrict();
            int strictGuards = 0;
            if (strict) {
                Set<String> paths = new LinkedHashSet<>();
                for (Expectation exp : expectations) {
                    if (exp.getHttpRequest() instanceof HttpRequest) {
                        HttpRequest req = (HttpRequest) exp.getHttpRequest();
                        if (req.getPath() != null && req.getPath().getValue() != null) {
                            paths.add(req.getPath().getValue());
                        }
                    }
                }
                for (String path : paths) {
                    // Build the body via the mapper so a path with JSON-special characters
                    // cannot produce malformed JSON.
                    String guardBody = objectMapper.createObjectNode()
                        .put("error", "strict VCR: no recorded fixture matched this request on path " + path)
                        .toString();
                    // Stable per-path id so re-loading upserts the same guard rather than
                    // accumulating duplicates across repeated load calls.
                    Expectation guard = Expectation.when(request().withPath(path), Integer.MIN_VALUE)
                        .withId("__llm_vcr_strict__" + path)
                        .thenRespond(HttpResponse.response()
                            .withStatusCode(599)
                            .withHeader("content-type", "application/json")
                            .withBody(guardBody));
                    httpState.add(guard);
                    strictGuards++;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "loaded");
            resultNode.put("count", addedExpectations.size());
            resultNode.put("file", inputPath.toString());
            resultNode.put("strict", strict);
            if (strictGuards > 0) {
                resultNode.put("strictGuards", strictGuards);
            }
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : addedExpectations) {
                ids.add(exp.getId());
            }
            resultNode.put("message", "Loaded " + addedExpectations.size() + " expectation(s) from " + inputPath
                + ". They are now active and will match incoming requests."
                + (strict ? " Strict VCR mode is on (" + strictGuards + " catch-all guard(s))." : ""));
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to load expectations from file", e);
        }
    }

    /**
     * Validates that a file path is inside the process working directory or the system temp directory.
     * Returns null if the path is allowed, or an error result if it is not.
     */
    static ObjectNode validateFilePath(Path filePath, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Path normalised = filePath.toAbsolutePath().normalize();
        Path workingDir = Paths.get("").toAbsolutePath().normalize();
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!normalised.startsWith(workingDir) && !normalised.startsWith(tempDir)) {
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("error", true);
            resultNode.put("message", "Path is outside allowed directories. Files must be inside the working directory ("
                + workingDir + ") or the system temp directory (" + tempDir + ")");
            return resultNode;
        }
        return null;
    }

    // --- mock_llm_completion ---

    private void registerMockLlmCompletion() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec (ANTHROPIC, OPENAI, OPENAI_RESPONSES, GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/messages)");
        properties.putObject("model").put("type", "string").put("description", "Model name (e.g. claude-sonnet-4, gpt-4o)");
        properties.putObject("text").put("type", "string").put("description", "Response text content");
        ObjectNode toolCallsProp = properties.putObject("toolCalls");
        toolCallsProp.put("type", "array").put("description", "Tool/function calls to include in the response");
        ObjectNode toolCallItems = toolCallsProp.putObject("items");
        toolCallItems.put("type", "object");
        ObjectNode toolCallProps = toolCallItems.putObject("properties");
        toolCallProps.putObject("name").put("type", "string").put("description", "Tool name");
        ObjectNode argsProp = toolCallProps.putObject("arguments");
        argsProp.put("description", "Tool arguments as a JSON string or object");
        ArrayNode argsAnyOf = argsProp.putArray("anyOf");
        argsAnyOf.add(objectMapper.createObjectNode().put("type", "string"));
        argsAnyOf.add(objectMapper.createObjectNode().put("type", "object"));
        toolCallItems.putArray("required").add("name");
        properties.putObject("stopReason").put("type", "string").put("description", "Stop reason (e.g. end_turn, tool_use, stop)");
        ObjectNode usageProp = properties.putObject("usage");
        usageProp.put("type", "object").put("description", "Token usage");
        ObjectNode usageProps = usageProp.putObject("properties");
        usageProps.putObject("inputTokens").put("type", "integer");
        usageProps.putObject("outputTokens").put("type", "integer");
        properties.putObject("streaming").put("type", "boolean").put("description", "Whether to stream the response (default false)");
        putChaosSchema(properties);
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");

        tools.put("mock_llm_completion", new ToolDefinition(
            "mock_llm_completion",
            "Creates a mock LLM completion expectation that returns a provider-correct response (Anthropic, OpenAI, etc.) from a high-level description of text, tool calls, and usage",
            schema,
            this::handleMockLlmCompletion
        ));
    }

    private JsonNode handleMockLlmCompletion(JsonNode params) {
        try {
            // Validate provider
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider;
            try {
                provider = Provider.valueOf(providerStr.trim());
            } catch (IllegalArgumentException e) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Pre-validate codec availability
            if (!ProviderCodecRegistry.getInstance().lookup(provider).isPresent()) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Validate path
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Build completion
            Completion completion = Completion.completion();
            JsonNode textNode = params.path("text");
            if (!textNode.isMissingNode() && !textNode.isNull()) {
                completion.withText(textNode.asText());
            }

            JsonNode stopReasonNode = params.path("stopReason");
            if (!stopReasonNode.isMissingNode() && !stopReasonNode.isNull()) {
                completion.withStopReason(stopReasonNode.asText());
            }

            // Tool calls
            JsonNode toolCallsNode = params.path("toolCalls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tcNode : toolCallsNode) {
                    String toolName = tcNode.path("name").asText(null);
                    if (toolName == null || toolName.trim().isEmpty()) {
                        return errorResult("each toolCalls entry must have a non-empty 'name'");
                    }
                    ToolUse toolUse = ToolUse.toolUse(toolName);
                    JsonNode argsNode = tcNode.path("arguments");
                    if (!argsNode.isMissingNode() && !argsNode.isNull()) {
                        if (argsNode.isTextual()) {
                            toolUse.withArguments(argsNode.asText());
                        } else if (argsNode.isObject()) {
                            toolUse.withArguments(objectMapper.writeValueAsString(argsNode));
                        } else {
                            return errorResult("toolCalls[].arguments must be a string or object");
                        }
                    }
                    completion.withToolCall(toolUse);
                }
            }

            // Usage
            JsonNode usageNode = params.path("usage");
            if (usageNode.isObject()) {
                Usage usage = Usage.usage();
                JsonNode inputTokensNode = usageNode.path("inputTokens");
                if (!inputTokensNode.isMissingNode() && !inputTokensNode.isNull()) {
                    if (!inputTokensNode.isIntegralNumber()) {
                        return errorResult("usage.inputTokens must be an integer");
                    }
                    usage.withInputTokens(inputTokensNode.asInt());
                }
                JsonNode outputTokensNode = usageNode.path("outputTokens");
                if (!outputTokensNode.isMissingNode() && !outputTokensNode.isNull()) {
                    if (!outputTokensNode.isIntegralNumber()) {
                        return errorResult("usage.outputTokens must be an integer");
                    }
                    usage.withOutputTokens(outputTokensNode.asInt());
                }
                completion.withUsage(usage);
            }

            // Streaming
            boolean streaming = params.path("streaming").asBoolean(false);
            if (streaming) {
                completion.streaming();
            }

            // Model
            String model = params.path("model").asText(null);

            // Build HttpLlmResponse
            HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
                .withProvider(provider)
                .withModel(model)
                .withCompletion(completion);
            LlmChaosProfile chaos = parseChaosProfile(params.path("chaos"));
            if (chaos != null) {
                llmResponse.withChaos(chaos);
            }

            // Build and register expectation
            Expectation expectation = Expectation.when(
                HttpRequest.request().withMethod("POST").withPath(path)
            ).thenRespondWithLlm(llmResponse);

            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            resultNode.put("provider", provider.name());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create LLM completion expectation", e);
        }
    }

    // --- create_llm_conversation ---

    private void registerCreateLlmConversation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec (ANTHROPIC, OPENAI, OPENAI_RESPONSES, GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/messages)");
        properties.putObject("model").put("type", "string").put("description", "Model name");
        ObjectNode isolateByProp = properties.putObject("isolateBy");
        isolateByProp.put("type", "object").put("description", "Per-session isolation configuration");
        ObjectNode isolateByProps = isolateByProp.putObject("properties");
        ObjectNode sourceProp = isolateByProps.putObject("source");
        sourceProp.put("type", "string").put("description", "Where to extract the isolation key: header, queryParameter, or cookie");
        ArrayNode sourceEnum = sourceProp.putArray("enum");
        sourceEnum.add("header");
        sourceEnum.add("queryParameter");
        sourceEnum.add("cookie");
        isolateByProps.putObject("name").put("type", "string").put("description", "Name of the header, query parameter, or cookie");
        ObjectNode turnsProp = properties.putObject("turns");
        turnsProp.put("type", "array").put("description", "Ordered list of conversation turns");
        ObjectNode turnItems = turnsProp.putObject("items");
        turnItems.put("type", "object");
        ObjectNode turnItemProps = turnItems.putObject("properties");

        // match predicates
        ObjectNode matchProp = turnItemProps.putObject("match");
        matchProp.put("type", "object").put("description", "Predicates for matching this turn");
        ObjectNode matchProps = matchProp.putObject("properties");
        matchProps.putObject("turnIndex").put("type", "integer").put("description", "Match when conversation has this many assistant turns (0-based)");
        matchProps.putObject("latestMessageContains").put("type", "string").put("description", "Match when latest message contains this substring");
        matchProps.putObject("latestMessageMatches").put("type", "string").put("description", "Match when latest message matches this Java regex pattern");
        matchProps.putObject("latestMessageRole").put("type", "string").put("description", "Match when latest message has this role");
        matchProps.putObject("containsToolResultFor").put("type", "string").put("description", "Match when conversation contains a tool result for this tool name");
        matchProps.putObject("semanticMatchAgainst").put("type", "string").put("description", "Opt-in fuzzy match: the intent the latest message should express, judged by a runtime LLM. Off unless mockserver.llmSemanticMatchingEnabled is set and a backend resolves; non-deterministic, exploratory only, never for assertions");

        // optional prompt normalisation applied before the latestMessage* text predicates
        ObjectNode normProp = matchProps.putObject("normalization");
        normProp.put("type", "object").put("description", "Optional deterministic normalisation applied to the latest-message text (and the latestMessageContains value) before matching, so cosmetic differences in dynamically-assembled prompts do not block a match");
        ObjectNode normProps = normProp.putObject("properties");
        normProps.putObject("collapseWhitespace").put("type", "boolean").put("description", "Collapse runs of whitespace to a single space and trim (default true)");
        normProps.putObject("lowercase").put("type", "boolean").put("description", "Lowercase the text before matching (default false)");
        normProps.putObject("sortJsonKeys").put("type", "boolean").put("description", "When the prompt is JSON, sort object keys so key ordering is irrelevant (default true)");
        normProps.putObject("dropBuiltInVolatileFields").put("type", "boolean").put("description", "Strip ISO-8601 timestamps, UUIDs, and prefix_… ids (req_/msg_/call_/…) before matching (default false)");
        ObjectNode dropFieldsProp = normProps.putObject("dropVolatileFields");
        dropFieldsProp.put("type", "array").put("description", "Names of JSON fields to drop from the prompt before matching");
        dropFieldsProp.putObject("items").put("type", "string");

        // response
        ObjectNode responseProp = turnItemProps.putObject("response");
        responseProp.put("type", "object").put("description", "Response configuration for this turn");
        ObjectNode responseProps = responseProp.putObject("properties");
        responseProps.putObject("text").put("type", "string").put("description", "Response text content");
        ObjectNode respToolCallsProp = responseProps.putObject("toolCalls");
        respToolCallsProp.put("type", "array").put("description", "Tool/function calls");
        ObjectNode respToolCallItems = respToolCallsProp.putObject("items");
        respToolCallItems.put("type", "object");
        ObjectNode respTcItemProps = respToolCallItems.putObject("properties");
        respTcItemProps.putObject("name").put("type", "string");
        ObjectNode respArgsProp = respTcItemProps.putObject("arguments");
        respArgsProp.put("description", "Tool arguments as string or object");
        ArrayNode respArgsAnyOf = respArgsProp.putArray("anyOf");
        respArgsAnyOf.add(objectMapper.createObjectNode().put("type", "string"));
        respArgsAnyOf.add(objectMapper.createObjectNode().put("type", "object"));
        responseProps.putObject("stopReason").put("type", "string").put("description", "Stop reason");
        ObjectNode respUsageProp = responseProps.putObject("usage");
        respUsageProp.put("type", "object");
        ObjectNode respUsageProps = respUsageProp.putObject("properties");
        respUsageProps.putObject("inputTokens").put("type", "integer");
        respUsageProps.putObject("outputTokens").put("type", "integer");
        responseProps.putObject("streaming").put("type", "boolean").put("description", "Whether to stream");

        // optional per-turn chaos profile
        putChaosSchema(turnItemProps);

        // Optional ids: array of expectation IDs to assign to the generated
        // expectations in turn order. When an id matches an existing
        // expectation the registration upserts in place — used by the
        // dashboard's "edit existing conversation" flow to keep IDs stable.
        ObjectNode idsProp = properties.putObject("ids");
        idsProp.put("type", "array").put("description", "Optional expectation IDs to assign per turn. Reusing the existing IDs updates the conversation in place.");
        idsProp.putObject("items").put("type", "string");

        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");
        required.add("turns");

        tools.put("create_llm_conversation", new ToolDefinition(
            "create_llm_conversation",
            "Creates a multi-turn LLM conversation mock with scenario-based state advancement. Each turn matches based on conversation predicates and returns a configured completion.",
            schema,
            this::handleCreateLlmConversation
        ));
    }

    private JsonNode handleCreateLlmConversation(JsonNode params) {
        try {
            // Validate provider
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider;
            try {
                provider = Provider.valueOf(providerStr.trim());
            } catch (IllegalArgumentException e) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Pre-validate codec availability
            if (!ProviderCodecRegistry.getInstance().lookup(provider).isPresent()) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Validate path
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Validate turns
            JsonNode turnsNode = params.path("turns");
            if (!turnsNode.isArray() || turnsNode.size() == 0) {
                return errorResult("'turns' must be a non-empty array");
            }

            // Model
            String model = params.path("model").asText(null);

            // Build conversation using LlmConversationBuilder
            LlmConversationBuilder conversationBuilder = LlmConversationBuilder.conversation()
                .withPath(path)
                .withProvider(provider)
                .withModel(model);

            // Isolation
            JsonNode isolateByNode = params.path("isolateBy");
            if (isolateByNode.isObject()) {
                String source = isolateByNode.path("source").asText(null);
                String isoName = isolateByNode.path("name").asText(null);
                if (source == null || isoName == null || isoName.trim().isEmpty()) {
                    return errorResult("isolateBy requires both 'source' and 'name'");
                }
                IsolationSource isolationSource;
                switch (source) {
                    case "header":
                        isolationSource = IsolationSource.header(isoName);
                        break;
                    case "queryParameter":
                        isolationSource = IsolationSource.queryParameter(isoName);
                        break;
                    case "cookie":
                        isolationSource = IsolationSource.cookie(isoName);
                        break;
                    default:
                        return errorResult("isolateBy.source must be one of: header, queryParameter, cookie");
                }
                conversationBuilder.isolateBy(isolationSource);
            }

            // Build turns
            for (int i = 0; i < turnsNode.size(); i++) {
                JsonNode turnNode = turnsNode.get(i);
                TurnBuilder turnBuilder = conversationBuilder.turn();

                // Match predicates
                JsonNode matchNode = turnNode.path("match");
                if (matchNode.isObject()) {
                    JsonNode turnIndexNode = matchNode.path("turnIndex");
                    if (!turnIndexNode.isMissingNode() && !turnIndexNode.isNull()) {
                        if (!turnIndexNode.isIntegralNumber()) {
                            return errorResult("turns[" + i + "].match.turnIndex must be an integer");
                        }
                        turnBuilder.whenTurnIndex(turnIndexNode.asInt());
                    }
                    JsonNode latestMsgContains = matchNode.path("latestMessageContains");
                    if (!latestMsgContains.isMissingNode() && !latestMsgContains.isNull()) {
                        turnBuilder.whenLatestMessageContains(latestMsgContains.asText());
                    }
                    JsonNode latestMsgMatches = matchNode.path("latestMessageMatches");
                    if (!latestMsgMatches.isMissingNode() && !latestMsgMatches.isNull()) {
                        try {
                            turnBuilder.whenLatestMessageMatches(
                                java.util.regex.Pattern.compile(latestMsgMatches.asText()));
                        } catch (java.util.regex.PatternSyntaxException e) {
                            return errorResult("turns[" + i + "].match.latestMessageMatches is not a valid regex: " + e.getMessage());
                        }
                    }
                    JsonNode latestMsgRole = matchNode.path("latestMessageRole");
                    if (!latestMsgRole.isMissingNode() && !latestMsgRole.isNull()) {
                        try {
                            turnBuilder.whenLatestMessageRole(
                                org.mockserver.llm.ParsedMessage.Role.valueOf(latestMsgRole.asText().toUpperCase()));
                        } catch (IllegalArgumentException e) {
                            return errorResult("turns[" + i + "].match.latestMessageRole must be one of: USER, ASSISTANT, SYSTEM, TOOL");
                        }
                    }
                    JsonNode containsToolResult = matchNode.path("containsToolResultFor");
                    if (!containsToolResult.isMissingNode() && !containsToolResult.isNull()) {
                        turnBuilder.whenContainsToolResultFor(containsToolResult.asText());
                    }
                    JsonNode semanticMatch = matchNode.path("semanticMatchAgainst");
                    if (!semanticMatch.isMissingNode() && !semanticMatch.isNull()) {
                        turnBuilder.whenSemanticMatch(semanticMatch.asText());
                    }
                    JsonNode normalizationNode = matchNode.path("normalization");
                    if (normalizationNode.isObject()) {
                        NormalizationOptions normalization = NormalizationOptions.normalizationOptions();
                        JsonNode collapseNode = normalizationNode.path("collapseWhitespace");
                        if (collapseNode.isBoolean()) {
                            normalization.withCollapseWhitespace(collapseNode.asBoolean());
                        }
                        JsonNode lowercaseNode = normalizationNode.path("lowercase");
                        if (lowercaseNode.isBoolean()) {
                            normalization.withLowercase(lowercaseNode.asBoolean());
                        }
                        JsonNode sortKeysNode = normalizationNode.path("sortJsonKeys");
                        if (sortKeysNode.isBoolean()) {
                            normalization.withSortJsonKeys(sortKeysNode.asBoolean());
                        }
                        JsonNode dropBuiltInNode = normalizationNode.path("dropBuiltInVolatileFields");
                        if (dropBuiltInNode.isBoolean()) {
                            normalization.withDropBuiltInVolatileFields(dropBuiltInNode.asBoolean());
                        }
                        JsonNode dropFieldsNode = normalizationNode.path("dropVolatileFields");
                        if (dropFieldsNode.isArray()) {
                            java.util.List<String> dropFields = new java.util.ArrayList<>();
                            for (JsonNode fieldNode : dropFieldsNode) {
                                if (fieldNode.isTextual()) {
                                    dropFields.add(fieldNode.asText());
                                }
                            }
                            normalization.withDropVolatileFields(dropFields);
                        }
                        turnBuilder.withNormalization(normalization);
                    }
                }

                // Response
                JsonNode responseNode = turnNode.path("response");
                if (responseNode.isObject()) {
                    Completion turnCompletion = Completion.completion();
                    JsonNode respTextNode = responseNode.path("text");
                    if (!respTextNode.isMissingNode() && !respTextNode.isNull()) {
                        turnCompletion.withText(respTextNode.asText());
                    }
                    JsonNode respStopNode = responseNode.path("stopReason");
                    if (!respStopNode.isMissingNode() && !respStopNode.isNull()) {
                        turnCompletion.withStopReason(respStopNode.asText());
                    }
                    JsonNode respToolCallsNode = responseNode.path("toolCalls");
                    if (respToolCallsNode.isArray()) {
                        for (JsonNode tcNode : respToolCallsNode) {
                            String toolName = tcNode.path("name").asText(null);
                            if (toolName == null || toolName.trim().isEmpty()) {
                                return errorResult("turns[" + i + "].response.toolCalls entry must have a non-empty 'name'");
                            }
                            ToolUse toolUse = ToolUse.toolUse(toolName);
                            JsonNode argsNode = tcNode.path("arguments");
                            if (!argsNode.isMissingNode() && !argsNode.isNull()) {
                                if (argsNode.isTextual()) {
                                    toolUse.withArguments(argsNode.asText());
                                } else if (argsNode.isObject()) {
                                    toolUse.withArguments(objectMapper.writeValueAsString(argsNode));
                                }
                            }
                            turnCompletion.withToolCall(toolUse);
                        }
                    }
                    JsonNode respUsageNode = responseNode.path("usage");
                    if (respUsageNode.isObject()) {
                        Usage usage = Usage.usage();
                        JsonNode inNode = respUsageNode.path("inputTokens");
                        if (!inNode.isMissingNode() && !inNode.isNull() && inNode.isIntegralNumber()) {
                            usage.withInputTokens(inNode.asInt());
                        }
                        JsonNode outNode = respUsageNode.path("outputTokens");
                        if (!outNode.isMissingNode() && !outNode.isNull() && outNode.isIntegralNumber()) {
                            usage.withOutputTokens(outNode.asInt());
                        }
                        turnCompletion.withUsage(usage);
                    }
                    boolean respStreaming = responseNode.path("streaming").asBoolean(false);
                    if (respStreaming) {
                        turnCompletion.streaming();
                    }
                    turnBuilder.respondingWith(turnCompletion);
                }

                // Optional chaos profile for this turn's response
                LlmChaosProfile turnChaos = parseChaosProfile(turnNode.path("chaos"));
                if (turnChaos != null) {
                    turnBuilder.withChaos(turnChaos);
                }

                // Chain to next (except last)
                if (i < turnsNode.size() - 1) {
                    turnBuilder.andThen();
                }
            }

            // Build expectations
            Expectation[] expectations = conversationBuilder.build();

            // Optional per-turn IDs — when supplied (and matching the turn
            // count), apply them so the registration upserts existing
            // expectations rather than allocating fresh UUIDs.
            JsonNode idsNode = params.path("ids");
            if (idsNode.isArray()) {
                for (int i = 0; i < expectations.length && i < idsNode.size(); i++) {
                    JsonNode idNode = idsNode.get(i);
                    if (idNode.isTextual()) {
                        String idVal = idNode.asText();
                        if (idVal != null && !idVal.isEmpty()) {
                            expectations[i].withId(idVal);
                        }
                    }
                }
            }

            // Register each expectation
            List<Expectation> allResults = new ArrayList<>();
            for (Expectation exp : expectations) {
                allResults.addAll(httpState.add(exp));
            }

            // Extract scenario name from first expectation
            String scenarioName = expectations.length > 0 ? expectations[0].getScenarioName() : null;

            // Build result with state info
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", allResults.size());
            if (scenarioName != null) {
                resultNode.put("scenarioName", scenarioName);
            }
            ArrayNode statesArray = resultNode.putArray("states");
            for (Expectation exp : expectations) {
                ObjectNode stateNode = objectMapper.createObjectNode();
                stateNode.put("scenarioState", exp.getScenarioState());
                stateNode.put("newScenarioState", exp.getNewScenarioState());
                if (exp.getId() != null) {
                    stateNode.put("id", exp.getId());
                }
                statesArray.add(stateNode);
            }
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : allResults) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (IllegalStateException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            return errorResult("Failed to create LLM conversation", e);
        }
    }

    // --- verify_tool_call ---

    private void registerVerifyToolCall() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded requests to inspect");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (e.g. /v1/messages)");
        properties.putObject("toolName").put("type", "string").put("description", "Name of the tool the agent should have called");
        properties.putObject("argumentsRegex").put("type", "string").put("description", "Optional Java regex matched against the tool call's argument JSON");
        properties.putObject("atLeast").put("type", "integer").put("description", "Minimum number of matching tool calls required (default 1)");
        properties.putObject("atMost").put("type", "integer").put("description", "Optional maximum number of matching tool calls allowed");
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("toolName");

        tools.put("verify_tool_call", new ToolDefinition(
            "verify_tool_call",
            "Assert that an agent called a named tool (optionally with arguments matching a regex) a given number of times, by inspecting LLM requests recorded through MockServer.",
            schema,
            this::handleVerifyToolCall
        ));
    }

    private JsonNode handleVerifyToolCall(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String toolName = params.path("toolName").asText(null);
            if (toolName == null || toolName.trim().isEmpty()) {
                return errorResult("'toolName' is required and must not be blank");
            }
            String path = emptyToNull(params.path("path").asText(null));
            String argumentsRegex = emptyToNull(params.path("argumentsRegex").asText(null));
            int atLeast = params.path("atLeast").isIntegralNumber() ? params.path("atLeast").asInt() : 1;
            Integer atMost = params.path("atMost").isIntegralNumber() ? params.path("atMost").asInt() : null;
            if (atMost != null && atMost < atLeast) {
                return errorResult("'atMost' must be greater than or equal to 'atLeast'");
            }

            List<HttpRequest> requests = retrieveRecordedHttpRequests(path);
            AgentRunAnalyzer.ToolCallReport report;
            try {
                report = new AgentRunAnalyzer().inspectToolCalls(requests, provider, toolName, argumentsRegex);
            } catch (IllegalArgumentException e) {
                return errorResult(e.getMessage());
            }
            boolean satisfied = report.getCount() >= atLeast && (atMost == null || report.getCount() <= atMost);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("toolName", toolName);
            resultNode.put("count", report.getCount());
            resultNode.put("atLeast", atLeast);
            if (atMost != null) {
                resultNode.put("atMost", atMost);
            }
            resultNode.put("satisfied", satisfied);
            if (!satisfied) {
                resultNode.put("message", "expected tool '" + toolName + "' to be called "
                    + (atMost != null ? "between " + atLeast + " and " + atMost : "at least " + atLeast)
                    + " time(s) but found " + report.getCount());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to verify tool call", e);
        }
    }

    // --- verify_structured_output ---

    private void registerVerifyStructuredOutput() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded responses to validate");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("schema").put("type", "string").put("description", "JSON Schema that the structured output text of each recorded response must conform to");
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (only validate responses to this path)");
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("schema");

        tools.put("verify_structured_output", new ToolDefinition(
            "verify_structured_output",
            "Validate that the structured (JSON) output of recorded LLM responses conforms to a JSON Schema. "
                + "Decodes each recorded response for the given provider, extracts the assistant's output text, and checks it "
                + "against the schema — use this to assert an agent (or mocked model) produced schema-valid structured output. "
                + "Read-only and deterministic; responses with no text output are reported separately as skipped.",
            schema,
            this::handleVerifyStructuredOutput
        ));
    }

    private JsonNode handleVerifyStructuredOutput(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String schemaStr = params.path("schema").asText(null);
            if (schemaStr == null || schemaStr.trim().isEmpty()) {
                return errorResult("'schema' is required and must not be blank");
            }
            Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
            if (!clientOpt.isPresent()) {
                return errorResult("no runtime client for provider " + provider + " to parse responses");
            }
            LlmClient client = clientOpt.get();

            JsonSchemaValidator validator;
            try {
                validator = new JsonSchemaValidator(mockServerLogger, schemaStr);
            } catch (Exception e) {
                return errorResult("'schema' is not a valid JSON Schema: " + e.getMessage());
            }

            String path = emptyToNull(params.path("path").asText(null));
            List<LogEventRequestAndResponse> pairs = retrieveRecordedPairs(path);

            int checked = 0;
            int conforming = 0;
            int skipped = 0;
            ArrayNode results = objectMapper.createArrayNode();
            for (LogEventRequestAndResponse pair : pairs) {
                HttpResponse recordedResponse = pair.getHttpResponse();
                if (recordedResponse == null) {
                    continue;
                }
                String outputText;
                try {
                    outputText = client.parseCompletionResponse(recordedResponse).getText();
                } catch (Exception e) {
                    outputText = null;
                }
                if (outputText == null || outputText.trim().isEmpty()) {
                    skipped++;
                    continue;
                }
                checked++;
                String error = validator.isValid(outputText, false);
                boolean conforms = error == null || error.isEmpty();
                if (conforms) {
                    conforming++;
                }
                ObjectNode entry = objectMapper.createObjectNode();
                if (pair.getHttpRequest() instanceof HttpRequest && ((HttpRequest) pair.getHttpRequest()).getPath() != null) {
                    entry.put("path", ((HttpRequest) pair.getHttpRequest()).getPath().getValue());
                }
                entry.put("conforms", conforms);
                if (!conforms) {
                    entry.put("error", error);
                }
                results.add(entry);
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("provider", provider.name());
            resultNode.put("checked", checked);
            resultNode.put("conforming", conforming);
            resultNode.put("nonConforming", checked - conforming);
            resultNode.put("skippedNoOutput", skipped);
            resultNode.put("allConform", checked > 0 && conforming == checked);
            resultNode.set("results", results);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to verify structured output", e);
        }
    }

    /**
     * Retrieve recorded request/response pairs (optionally filtered by path) from
     * the running server's event log.
     */
    private List<LogEventRequestAndResponse> retrieveRecordedPairs(String path) {
        HttpRequest filter = request();
        if (path != null && !path.isEmpty()) {
            filter.withPath(path);
        }
        HttpRequest retrieveRequest = request()
            .withMethod("PUT")
            .withPath("/mockserver/retrieve")
            .withQueryStringParameter("type", "REQUEST_RESPONSES")
            .withQueryStringParameter("format", "JSON")
            .withBody(getRequestDefinitionSerializer().serialize(filter));
        HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
        String body = retrieveResponse.getBodyAsString();
        List<LogEventRequestAndResponse> result = new ArrayList<>();
        if (body != null && !body.trim().isEmpty()) {
            try {
                LogEventRequestAndResponse[] pairs =
                    new LogEventRequestAndResponseSerializer(mockServerLogger).deserializeArray(body);
                result.addAll(Arrays.asList(pairs));
            } catch (IllegalArgumentException e) {
                // No parseable request/response pairs (e.g. an empty "[]" result) — treat as none.
            }
        }
        return result;
    }

    // --- explain_agent_run ---

    private void registerExplainAgentRun() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded requests to summarise");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (e.g. /v1/messages)");
        ArrayNode required = schema.putArray("required");
        required.add("provider");

        tools.put("explain_agent_run", new ToolDefinition(
            "explain_agent_run",
            "Summarise an agent run reconstructed from recorded LLM requests: message and assistant-turn counts, the ordered tool-call sequence, tool results, and the latest message role.",
            schema,
            this::handleExplainAgentRun
        ));
    }

    private JsonNode handleExplainAgentRun(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String path = emptyToNull(params.path("path").asText(null));
            List<HttpRequest> requests = retrieveRecordedHttpRequests(path);
            Optional<AgentRunAnalyzer.RunSummary> summaryOpt = new AgentRunAnalyzer().summarise(requests, provider);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (!summaryOpt.isPresent()) {
                resultNode.put("message", "no decodable " + provider + " conversation found in recorded requests");
                resultNode.put("messageCount", 0);
                return resultNode;
            }
            AgentRunAnalyzer.RunSummary summary = summaryOpt.get();
            resultNode.put("messageCount", summary.getMessageCount());
            resultNode.put("assistantTurnCount", summary.getAssistantTurnCount());
            ArrayNode toolCalls = resultNode.putArray("toolCallSequence");
            summary.getToolCallSequence().forEach(toolCalls::add);
            ArrayNode toolResults = resultNode.putArray("toolResultsFor");
            summary.getToolResultsFor().forEach(toolResults::add);
            resultNode.put("latestMessageRole", summary.getLatestMessageRole());

            // correlated call graph (nodes + directed edges)
            AgentRunAnalyzer.CallGraph graph = new AgentRunAnalyzer().buildCallGraph(requests, provider);
            ObjectNode graphNode = resultNode.putObject("callGraph");
            ArrayNode graphNodes = graphNode.putArray("nodes");
            for (AgentRunAnalyzer.GraphNode node : graph.getNodes()) {
                ObjectNode n = graphNodes.addObject();
                n.put("id", node.getId());
                n.put("kind", node.getKind());
                n.put("label", node.getLabel());
            }
            ArrayNode graphEdges = graphNode.putArray("edges");
            for (AgentRunAnalyzer.GraphEdge edge : graph.getEdges()) {
                ObjectNode e = graphEdges.addObject();
                e.put("from", edge.getFrom());
                e.put("to", edge.getTo());
                e.put("kind", edge.getKind());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to explain agent run", e);
        }
    }

    // --- detect_llm_drift ---

    private void registerDetectLlmDrift() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("cassettePath").put("type", "string").put("description",
            "Path to a recorded fixture (cassette) JSON file whose exchanges are replayed against the live provider");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider the cassette was recorded against");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("backendName").put("type", "string").put("description",
            "Optional named backend from mockserver.llmBackendsConfig; otherwise the default backend is resolved from env/properties");
        ArrayNode required = schema.putArray("required");
        required.add("cassettePath");
        required.add("provider");

        tools.put("detect_llm_drift", new ToolDefinition(
            "detect_llm_drift",
            "Replays a recorded LLM cassette against the live provider and reports STRUCTURAL drift in the responses "
                + "(new/removed fields, type changes) — not semantic differences. Requires a configured runtime LLM backend "
                + "(env vars, mockserver.llmProvider/llmApiKey, or a named backend); if none is configured the tool is disabled. "
                + "Fails closed per exchange: a network error or non-2xx live response is reported as could-not-check, never as drift. "
                + "Intended for an opt-in/scheduled CI lane, not the per-commit build.",
            schema,
            this::handleDetectLlmDrift
        ));
    }

    private JsonNode handleDetectLlmDrift(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String cassettePath = params.path("cassettePath").asText(null);
            if (cassettePath == null || cassettePath.trim().isEmpty()) {
                return errorResult("'cassettePath' is required and must not be blank");
            }
            Path inputPath = Paths.get(cassettePath).toAbsolutePath().normalize();
            ObjectNode pathError = validateFilePath(inputPath, objectMapper);
            if (pathError != null) {
                return pathError;
            }
            if (!Files.exists(inputPath) || !Files.isReadable(inputPath)) {
                return errorResult("Cassette file does not exist or is not readable: " + inputPath);
            }

            // Resolve a backend (off unless configured)
            LlmBackendResolver resolver = new LlmBackendResolver();
            String backendName = emptyToNull(params.path("backendName").asText(null));
            Optional<LlmBackend> backendOpt = backendName != null ? resolver.resolveByName(backendName) : resolver.resolveDefault();
            if (!backendOpt.isPresent()) {
                ObjectNode disabled = objectMapper.createObjectNode();
                disabled.put("disabled", true);
                disabled.put("message", "No runtime LLM backend configured — drift detection is off. Set OPENAI_API_KEY/"
                    + "ANTHROPIC_API_KEY/GEMINI_API_KEY/OLLAMA_HOST, the mockserver.llm* properties, or mockserver.llmBackendsConfig.");
                return disabled;
            }
            LlmBackend backend = backendOpt.get();

            // Extract recorded exchanges (non-streaming JSON responses)
            String json = new String(Files.readAllBytes(inputPath), StandardCharsets.UTF_8);
            Expectation[] expectations = getExpectationSerializer().deserializeArray(json, false);
            List<DriftDetector.RecordedExchange> exchanges = new ArrayList<>();
            int skippedSse = 0;
            for (Expectation exp : expectations) {
                if (exp.getHttpRequest() instanceof HttpRequest && exp.getHttpResponse() != null) {
                    exchanges.add(new DriftDetector.RecordedExchange(
                        (HttpRequest) exp.getHttpRequest(), exp.getHttpResponse().getBodyAsString()));
                } else if (exp.getHttpSseResponse() != null) {
                    skippedSse++;
                }
            }
            if (exchanges.isEmpty()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("status", "no_exchanges");
                empty.put("message", "No non-streaming exchanges found in cassette to check"
                    + (skippedSse > 0 ? " (" + skippedSse + " streaming exchange(s) skipped)" : ""));
                return empty;
            }

            // Build a transient transport for the live calls and run the detector.
            // Transient per-call is acceptable for the opt-in/scheduled CI lane this
            // tool targets; promote to a shared instance if usage becomes frequent.
            long timeout = backend.timeoutMillis() != null ? backend.timeoutMillis() : ConfigurationProperties.llmRequestTimeoutMillis();
            io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
            DriftReport report;
            try {
                NettyHttpClient httpClient = new NettyHttpClient(httpState.getConfiguration(), mockServerLogger, group, null, false);
                report = new DriftDetector(new NettyHttpClientLlmTransport(httpClient), timeout)
                    .detect(exchanges, provider, backend);
            } finally {
                // bounded wind-down so threads do not accumulate across rapid calls
                group.shutdownGracefully(0, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "checked");
            // surface a likely misconfiguration: the cassette's provider and the
            // resolved backend's provider differ, so every live call will mis-target
            if (backend.provider() != provider) {
                resultNode.put("warning", "resolved backend provider " + backend.provider()
                    + " does not match cassette provider " + provider + "; live calls may all fail");
            }
            resultNode.put("exchanges", exchanges.size());
            resultNode.put("drifted", report.driftedCount());
            resultNode.put("checked", report.checkedCount());
            resultNode.put("couldNotCheck", report.couldNotCheckCount());
            if (skippedSse > 0) {
                resultNode.put("skippedStreaming", skippedSse);
            }
            ArrayNode details = resultNode.putArray("details");
            for (DriftReport.ExchangeDrift ex : report.getExchanges()) {
                ObjectNode node = details.addObject();
                node.put("index", ex.getIndex());
                node.put("status", ex.getStatus().name());
                if (ex.getNote() != null) {
                    node.put("note", ex.getNote());
                }
                if (!ex.getAddedPaths().isEmpty()) {
                    ArrayNode a = node.putArray("addedPaths");
                    ex.getAddedPaths().forEach(a::add);
                }
                if (!ex.getRemovedPaths().isEmpty()) {
                    ArrayNode r = node.putArray("removedPaths");
                    ex.getRemovedPaths().forEach(r::add);
                }
                if (!ex.getTypeChangedPaths().isEmpty()) {
                    ArrayNode t = node.putArray("typeChangedPaths");
                    ex.getTypeChangedPaths().forEach(t::add);
                }
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to detect LLM drift", e);
        }
    }

    // --- mock_adversarial_llm_response ---

    private void registerMockAdversarialLlmResponse() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/messages)");
        properties.putObject("model").put("type", "string").put("description", "Model name");
        ObjectNode payloadProp = properties.putObject("payload");
        payloadProp.put("type", "string").put("description", "Adversarial payload id — one of the enum values below");
        ArrayNode payloadEnum = payloadProp.putArray("enum");
        for (String id : org.mockserver.llm.adversarial.AdversarialResponseLibrary.ids()) {
            payloadEnum.add(id);
        }
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");
        required.add("payload");

        tools.put("mock_adversarial_llm_response", new ToolDefinition(
            "mock_adversarial_llm_response",
            "Creates a mock LLM completion whose response is a curated ADVERSARIAL payload "
                + "(prompt injection, jailbreak, data-exfiltration request, malformed/oversized content) so you can "
                + "test that your agent RESISTS hostile or malformed model/tool output. Defensive testing aid — the "
                + "payloads are benign test fixtures, not working exploits. Deterministic.",
            schema,
            this::handleMockAdversarialLlmResponse
        ));
    }

    private JsonNode handleMockAdversarialLlmResponse(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }
            String payloadId = params.path("payload").asText(null);
            if (payloadId == null || payloadId.trim().isEmpty()) {
                return errorResult("'payload' is required");
            }
            Optional<org.mockserver.llm.adversarial.AdversarialResponseLibrary.Payload> payload =
                org.mockserver.llm.adversarial.AdversarialResponseLibrary.get(payloadId);
            if (!payload.isPresent()) {
                return errorResult("unknown adversarial payload '" + payloadId + "'; available: "
                    + org.mockserver.llm.adversarial.AdversarialResponseLibrary.ids());
            }

            Completion completion = Completion.completion().withText(payload.get().getText());
            HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
                .withProvider(provider)
                .withModel(params.path("model").asText(null))
                .withCompletion(completion);
            Expectation expectation = Expectation.when(
                HttpRequest.request().withMethod("POST").withPath(path)
            ).thenRespondWithLlm(llmResponse);
            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            resultNode.put("payload", payloadId);
            resultNode.put("category", payload.get().getCategory());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create adversarial LLM response", e);
        }
    }

    private Provider parseProviderParam(JsonNode params) {
        String providerStr = params.path("provider").asText(null);
        if (providerStr == null || providerStr.trim().isEmpty()) {
            return null;
        }
        try {
            Provider provider = Provider.valueOf(providerStr.trim());
            return ProviderCodecRegistry.getInstance().lookup(provider).isPresent() ? provider : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * Replay normalisation: if the expectation's request has a JSON body, drop the
     * named (volatile) fields and match the remainder loosely (extra fields in the
     * incoming request are ignored), so volatile values do not block replay.
     */
    private void relaxRequestBodyForReplay(Expectation expectation, List<String> dropFields) {
        if (!(expectation.getHttpRequest() instanceof HttpRequest)) {
            return;
        }
        HttpRequest request = (HttpRequest) expectation.getHttpRequest();
        String bodyString = request.getBodyAsString();
        if (bodyString == null || bodyString.isEmpty()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(bodyString);
            if (root != null && (root.isObject() || root.isArray())) {
                Set<String> dropSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                dropSet.addAll(dropFields);
                removeFieldsRecursively(root, dropSet);
                request.withBody(org.mockserver.model.JsonBody.json(objectMapper.writeValueAsString(root), MatchType.ONLY_MATCHING_FIELDS));
            }
        } catch (Exception e) {
            // not JSON — leave the body matcher unchanged
        }
    }

    private void removeFieldsRecursively(JsonNode node, Set<String> dropFields) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> toRemove = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                if (dropFields.contains(name)) {
                    toRemove.add(name);
                }
            });
            toRemove.forEach(object::remove);
            object.fields().forEachRemaining(e -> removeFieldsRecursively(e.getValue(), dropFields));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                removeFieldsRecursively(child, dropFields);
            }
        }
    }

    /**
     * Add the optional {@code chaos} object schema to a tool's properties.
     */
    private void putChaosSchema(ObjectNode properties) {
        ObjectNode chaosProp = properties.putObject("chaos");
        chaosProp.put("type", "object").put("description", "Optional fault/chaos profile for resilience testing");
        ObjectNode chaosProps = chaosProp.putObject("properties");
        chaosProps.putObject("errorStatus").put("type", "integer").put("description", "HTTP error status to return (e.g. 429, 529); fires always unless errorProbability is set");
        chaosProps.putObject("retryAfter").put("type", "string").put("description", "Value for the Retry-After header on an injected error");
        chaosProps.putObject("errorProbability").put("type", "number").put("description", "Probability 0.0-1.0 of injecting the error (default 1.0 when errorStatus set)");
        ObjectNode truncateModeProp = chaosProps.putObject("truncateMode");
        truncateModeProp.put("type", "string").put("description", "Streaming truncation mode");
        ArrayNode truncateEnum = truncateModeProp.putArray("enum");
        truncateEnum.add("NONE");
        truncateEnum.add("MID_STREAM");
        chaosProps.putObject("truncateAtFraction").put("type", "number").put("description", "Fraction 0.0-1.0 of SSE events to keep before truncating (default 0.5)");
        chaosProps.putObject("malformedSse").put("type", "boolean").put("description", "Append a malformed (broken-JSON) SSE chunk");
        chaosProps.putObject("seed").put("type", "integer").put("description", "Seed making a fractional errorProbability reproducible");
    }

    /**
     * Parse an optional chaos profile object into an {@link LlmChaosProfile}, or
     * null if the node is absent/empty.
     */
    private LlmChaosProfile parseChaosProfile(JsonNode node) {
        if (node == null || !node.isObject() || node.size() == 0) {
            return null;
        }
        LlmChaosProfile chaos = LlmChaosProfile.llmChaosProfile();
        if (node.path("errorStatus").isIntegralNumber()) {
            chaos.withErrorStatus(node.path("errorStatus").asInt());
        }
        if (node.path("retryAfter").isTextual()) {
            chaos.withRetryAfter(node.path("retryAfter").asText());
        }
        if (node.path("errorProbability").isNumber()) {
            chaos.withErrorProbability(node.path("errorProbability").asDouble());
        }
        if (node.path("truncateMode").isTextual()) {
            try {
                chaos.withTruncateMode(LlmChaosProfile.TruncateMode.valueOf(node.path("truncateMode").asText().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // leave unset on an unrecognised value
            }
        }
        if (node.path("truncateAtFraction").isNumber()) {
            chaos.withTruncateAtFraction(node.path("truncateAtFraction").asDouble());
        }
        if (node.path("malformedSse").isBoolean()) {
            chaos.withMalformedSse(node.path("malformedSse").asBoolean());
        }
        if (node.path("seed").isIntegralNumber()) {
            chaos.withSeed(node.path("seed").asLong());
        }
        return chaos;
    }

    /**
     * Retrieve the requests recorded by MockServer (optionally filtered by path)
     * as concrete {@link HttpRequest}s for LLM analysis.
     */
    private List<HttpRequest> retrieveRecordedHttpRequests(String path) {
        HttpRequest filter = request();
        if (path != null && !path.isEmpty()) {
            filter.withPath(path);
        }
        HttpRequest retrieveRequest = request()
            .withMethod("PUT")
            .withPath("/mockserver/retrieve")
            .withQueryStringParameter("type", "REQUESTS")
            .withQueryStringParameter("format", "JSON")
            .withBody(getRequestDefinitionSerializer().serialize(filter));
        HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
        String body = retrieveResponse.getBodyAsString();
        List<HttpRequest> result = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            RequestDefinition[] definitions = getRequestDefinitionSerializer().deserializeArray(body);
            for (RequestDefinition definition : definitions) {
                if (definition instanceof HttpRequest) {
                    result.add((HttpRequest) definition);
                }
            }
        }
        return result;
    }

    private ObjectNode errorResult(String message) {
        ObjectNode resultNode = objectMapper.createObjectNode();
        resultNode.put("error", true);
        resultNode.put("message", message);
        return resultNode;
    }

    private ObjectNode errorResult(String message, Throwable throwable) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat("MCP tool error: {}")
                .setArguments(message)
                .setThrowable(throwable)
        );
        return errorResult(message);
    }

    private ObjectNode unsupportedLlmProviderResult(String providerStr) {
        ObjectNode resultNode = objectMapper.createObjectNode();
        resultNode.put("error", true);
        resultNode.put("message", "unsupported LLM provider: " + providerStr);
        ArrayNode supported = resultNode.putArray("supported");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            supported.add(name);
        }
        return resultNode;
    }

    public static class ToolDefinition {
        private final String name;
        private final String description;
        private final JsonNode inputSchema;
        private final Function<JsonNode, JsonNode> handler;

        public ToolDefinition(String name, String description, JsonNode inputSchema, Function<JsonNode, JsonNode> handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public JsonNode getInputSchema() {
            return inputSchema;
        }

        public Function<JsonNode, JsonNode> getHandler() {
            return handler;
        }
    }
}
