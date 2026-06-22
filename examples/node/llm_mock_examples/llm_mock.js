/*
 * MockServer Node client -- LLM mock example.
 *
 * Mocks an OpenAI-style chat completion using the client's fluent LLM mock
 * builder (client.llm.llmMock(...)). MockServer renders the canned completion
 * into the provider's native wire format, so an OpenAI SDK pointed at this
 * MockServer receives a real-looking chat completion response.
 *
 * Run against a MockServer on localhost:1080.
 */
var mockServerClient = require('mockserver-client').mockServerClient;

var client = mockServerClient("localhost", 1080);
var llm = client.llm;

// Mock POST /v1/chat/completions for the OpenAI provider, returning a fixed
// assistant message with token-usage counts.
client.mockWithLLM(
    llm.llmMock("/v1/chat/completions")
        .withProvider(llm.Provider.OPENAI)
        .withModel("gpt-4o")
        .respondingWith(
            llm.completion()
                .withText("Paris is the capital of France.")
                .withStopReason("stop")
                .withUsage(llm.usage().withInputTokens(12).withOutputTokens(8))
        )
        .build()
).then(
    function () {
        console.log("expectation created: OpenAI chat completion mock on /v1/chat/completions");
    },
    function (error) {
        console.log(error);
    }
);
