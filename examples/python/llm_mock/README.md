# LLM Mock Example

## What it demonstrates

Mocking an OpenAI-style chat completion with the Python client's fluent LLM mock
builder (`llm_mock(...)`). The builder produces an `httpLlmResponse` expectation;
MockServer renders the canned completion into the provider's native wire format,
so an OpenAI SDK pointed at MockServer gets a realistic response. The example
sets the provider, model, completion text, stop reason, and token usage.

## Prerequisites

- Python 3.9+
- `pip install mockserver-client` (or `pip install -e ../../mockserver-client-python`)
- MockServer running on `localhost:1080`

## Run

```bash
python llm_mock.py
```

## Expected output

```
expectation created: OpenAI chat completion mock on /v1/chat/completions

All LLM mock expectations created successfully.
```
