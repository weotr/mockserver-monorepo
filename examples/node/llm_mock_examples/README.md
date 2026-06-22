# LLM Mock Examples

## What it demonstrates

Mocking an OpenAI-style chat completion with the Node client's fluent LLM mock
builder (`client.llm.llmMock(...)`). The builder produces an `httpLlmResponse`
expectation; MockServer renders the canned completion into the provider's native
wire format, so an OpenAI SDK pointed at MockServer gets a realistic response.
The example sets the provider, model, completion text, stop reason, and token
usage.

## Prerequisites

- Node.js
- `npm install` (installs `mockserver-client`)
- MockServer running on `localhost:1080`

## Run

```bash
npm install
node llm_mock.js
```

## Expected output

```
expectation created: OpenAI chat completion mock on /v1/chat/completions
```
