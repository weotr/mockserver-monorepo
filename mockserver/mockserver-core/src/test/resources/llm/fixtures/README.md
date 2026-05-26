# LLM Codec Fixture Files

This directory holds golden-file fixtures for LLM provider codec testing.
Each provider has its own subdirectory containing captured real API responses
that are diffed against the codec's encode output.

See [docs/code/llm-codec-fixtures.md](../../../../../../../docs/code/llm-codec-fixtures.md)
for the full golden-file refresh process.

## Directory layout

```
fixtures/
  anthropic/          Anthropic Messages API responses
  openai/             OpenAI Chat Completions API responses
  openai-responses/   OpenAI Responses API responses
  gemini/             Google Gemini generateContent API responses
  bedrock/            AWS Bedrock (Anthropic-on-Bedrock) responses
  azure-openai/       Azure OpenAI Chat Completions responses
  ollama/             Ollama /api/chat responses
```

## Status

Fixture directories are currently empty (`.gitkeep` only). Capturing live
responses requires provider API keys and is tracked as a follow-up task.
