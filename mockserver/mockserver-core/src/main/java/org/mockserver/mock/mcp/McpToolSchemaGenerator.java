package org.mockserver.mock.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates <a href="https://modelcontextprotocol.io/">Model Context Protocol</a> tool definitions
 * from the current mock expectations, so an AI agent can discover and (via an MCP client) call the
 * mocked endpoints as tools. Each response expectation with a concrete request matcher becomes one
 * tool: the tool name is derived from the method and path, the {@code inputSchema} exposes the
 * query parameters and (when present) a request body, and a {@code _mockserver} annotation records
 * the target method/path/expectation id so a caller can route an invocation back to the mock.
 *
 * <p>Expectations whose method or path is a notted ("match anything except") matcher are skipped,
 * as are non-response actions — neither has a meaningful single-tool representation.
 */
public class McpToolSchemaGenerator {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    /**
     * @param expectations the expectations to expose (typically the active expectations)
     * @return an array of MCP tool definition objects (name, description, inputSchema, _mockserver)
     */
    public ArrayNode generate(List<Expectation> expectations) {
        final ArrayNode tools = objectMapper.createArrayNode();
        if (expectations == null) {
            return tools;
        }
        final Set<String> usedNames = new HashSet<>();
        for (final Expectation expectation : expectations) {
            if (!(expectation.getHttpRequest() instanceof HttpRequest)) {
                continue;
            }
            final HttpRequest request = (HttpRequest) expectation.getHttpRequest();
            if (isNotted(request.getMethod()) || isNotted(request.getPath())) {
                continue;
            }
            if (!hasResponseAction(expectation)) {
                continue;
            }
            final String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
            final String path = request.getPath() != null ? request.getPath().getValue() : "/";

            final ObjectNode tool = tools.addObject();
            tool.put("name", uniqueName(toolName(method, path), usedNames));
            tool.put("description", "Mock for " + method + " " + path);
            buildInputSchema(tool.putObject("inputSchema"), request);

            final ObjectNode meta = tool.putObject("_mockserver");
            meta.put("method", method);
            meta.put("path", path);
            if (expectation.getId() != null) {
                meta.put("expectationId", expectation.getId());
            }
        }
        return tools;
    }

    private void buildInputSchema(ObjectNode schema, HttpRequest request) {
        schema.put("type", "object");
        final ObjectNode properties = schema.putObject("properties");
        final Parameters query = request.getQueryStringParameters();
        if (query != null && !query.isEmpty()) {
            for (final Parameter parameter : query.getEntries()) {
                if (isNotted(parameter.getName())) {
                    continue;
                }
                properties.putObject(parameter.getName().getValue())
                    .put("type", "string")
                    .put("description", "query parameter");
            }
        }
        if (request.getBody() != null) {
            final ObjectNode bodyProperty = properties.putObject("body");
            bodyProperty.put("description", "request body");
            final ArrayNode anyOf = bodyProperty.putArray("anyOf");
            anyOf.add(objectMapper.createObjectNode().put("type", "string"));
            anyOf.add(objectMapper.createObjectNode().put("type", "object"));
        }
    }

    /** Turns a method + path into a valid MCP tool name (lower snake_case, <= 64 chars). */
    private static String toolName(String method, String path) {
        String name = (method + "_" + path).toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (name.isEmpty()) {
            name = "tool";
        }
        if (name.length() > MAX_TOOL_NAME_LENGTH) {
            name = name.substring(0, MAX_TOOL_NAME_LENGTH);
        }
        return name;
    }

    private static String uniqueName(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            final String suffixStr = "_" + suffix++;
            // keep the deduplicated name within the MCP tool-name length limit
            final String trimmedBase = base.length() + suffixStr.length() > MAX_TOOL_NAME_LENGTH
                ? base.substring(0, MAX_TOOL_NAME_LENGTH - suffixStr.length())
                : base;
            candidate = trimmedBase + suffixStr;
        }
        return candidate;
    }

    private static boolean hasResponseAction(Expectation expectation) {
        if (expectation.getHttpResponse() != null) {
            return true;
        }
        final List<HttpResponse> responses = expectation.getHttpResponses();
        return responses != null && !responses.isEmpty();
    }

    private static boolean isNotted(NottableString value) {
        return value != null && value.isNot();
    }
}
