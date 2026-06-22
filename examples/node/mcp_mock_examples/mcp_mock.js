/*
 * MockServer Node client -- MCP (Model Context Protocol) mock example.
 *
 * Mocks an MCP server that speaks JSON-RPC 2.0 over the Streamable HTTP
 * transport using the client's fluent MCP mock builder (client.mcpMock(...)).
 * The builder registers the initialize/ping/tools-list/tools-call expectations
 * needed for an MCP client to discover and invoke the tool.
 *
 * Run against a MockServer on localhost:1080.
 */
var mockServerClient = require('mockserver-client').mockServerClient;

var client = mockServerClient("localhost", 1080);

// Mock an MCP server mounted on /mcp exposing a single "get_weather" tool.
client.mcpMock("/mcp")
    .withServerName("WeatherServer")
    .withTool("get_weather")
        .withDescription("Get the current weather for a city")
        .withInputSchema('{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}')
        .respondingWith("It is sunny and 21C in the requested city.")
    .and()
    .applyTo(client)
    .then(
        function () {
            console.log("expectation created: MCP server 'WeatherServer' with tool 'get_weather' on /mcp");
        },
        function (error) {
            console.log(error);
        }
    );
