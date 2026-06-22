#!/usr/bin/env python3
"""MockServer Python client -- LLM mock example.

Mocks an OpenAI-style chat completion using the client's fluent LLM mock
builder (``llm_mock(...)``). MockServer renders the canned completion into the
provider's native wire format, so an OpenAI SDK pointed at MockServer receives a
real-looking chat completion response.

Run this script against a MockServer on localhost:1080.
"""

from mockserver import (
    MockServerClient,
    Provider,
    completion,
    llm_mock,
    usage,
)


MOCK_HOST = "localhost"
MOCK_PORT = 1080


def openai_chat_completion(client: MockServerClient) -> None:
    """Mock POST /v1/chat/completions for the OpenAI provider."""
    llm_mock("/v1/chat/completions").with_provider(Provider.OPENAI).with_model(
        "gpt-4o"
    ).responding_with(
        completion(
            text="Paris is the capital of France.",
            stop_reason="stop",
            usage=usage(input_tokens=12, output_tokens=8),
        )
    ).apply_to(client)
    print("expectation created: OpenAI chat completion mock on /v1/chat/completions")


def main() -> None:
    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        client.reset()

        openai_chat_completion(client)

        print("\nAll LLM mock expectations created successfully.")


if __name__ == "__main__":
    main()
