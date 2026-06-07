import { describe, it, expect } from 'vitest';
import { estimateCostUsd } from '../lib/llmPricing';

describe('estimateCostUsd', () => {
  // -------------------------------------------------------------------------
  // Anthropic models
  // -------------------------------------------------------------------------

  it('returns expected cost for claude-sonnet-4 model', () => {
    const cost = estimateCostUsd('anthropic', 'claude-sonnet-4-20250514', 1_000_000, 1_000_000);
    // 3.00 input + 15.00 output = 18.00
    expect(cost).toBeCloseTo(18.0, 2);
  });

  it('returns expected cost for claude-opus-4 model', () => {
    const cost = estimateCostUsd('anthropic', 'claude-opus-4-20250514', 1_000_000, 1_000_000);
    // 15.00 input + 75.00 output = 90.00
    expect(cost).toBeCloseTo(90.0, 2);
  });

  it('returns expected cost for claude-haiku-4 model', () => {
    const cost = estimateCostUsd('anthropic', 'claude-haiku-4-20250514', 1_000_000, 1_000_000);
    // 0.80 input + 4.00 output = 4.80
    expect(cost).toBeCloseTo(4.8, 2);
  });

  // -------------------------------------------------------------------------
  // OpenAI models
  // -------------------------------------------------------------------------

  it('returns expected cost for gpt-4o', () => {
    const cost = estimateCostUsd('openai', 'gpt-4o', 1_000_000, 1_000_000);
    // 2.50 input + 10.00 output = 12.50
    expect(cost).toBeCloseTo(12.5, 2);
  });

  it('returns expected cost for gpt-4o-mini', () => {
    const cost = estimateCostUsd('openai', 'gpt-4o-mini', 1_000_000, 1_000_000);
    // 0.15 input + 0.60 output = 0.75
    expect(cost).toBeCloseTo(0.75, 2);
  });

  it('returns expected cost for o3 (OpenAI Responses)', () => {
    const cost = estimateCostUsd('openai_responses', 'o3-2025-04-16', 1_000_000, 1_000_000);
    // 15.00 input + 60.00 output = 75.00
    expect(cost).toBeCloseTo(75.0, 2);
  });

  // -------------------------------------------------------------------------
  // Gemini
  // -------------------------------------------------------------------------

  it('returns expected cost for gemini-2.0-flash', () => {
    const cost = estimateCostUsd('gemini', 'gemini-2.0-flash', 1_000_000, 1_000_000);
    // 0.10 input + 0.40 output = 0.50
    expect(cost).toBeCloseTo(0.5, 2);
  });

  // -------------------------------------------------------------------------
  // Bedrock defers to Anthropic pricing
  // -------------------------------------------------------------------------

  it('returns Anthropic pricing for Bedrock anthropic.claude-sonnet-4 models', () => {
    const cost = estimateCostUsd('bedrock', 'anthropic.claude-sonnet-4-20250514-v1:0', 1_000_000, 1_000_000);
    expect(cost).toBeCloseTo(18.0, 2);
  });

  // -------------------------------------------------------------------------
  // Azure OpenAI defers to OpenAI pricing
  // -------------------------------------------------------------------------

  it('returns OpenAI pricing for Azure OpenAI gpt-4o', () => {
    const cost = estimateCostUsd('azure_openai', 'gpt-4o', 1_000_000, 1_000_000);
    expect(cost).toBeCloseTo(12.5, 2);
  });

  // -------------------------------------------------------------------------
  // Ollama is always free
  // -------------------------------------------------------------------------

  it('returns 0 for Ollama models regardless of token count', () => {
    const cost = estimateCostUsd('ollama', 'llama3:latest', 10_000, 5_000);
    expect(cost).toBe(0);
  });

  it('returns 0 for Ollama with zero tokens', () => {
    const cost = estimateCostUsd('ollama', 'anything', 0, 0);
    expect(cost).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Unknown model
  // -------------------------------------------------------------------------

  it('returns null for an unknown Anthropic model', () => {
    expect(estimateCostUsd('anthropic', 'claude-ancient-1', 1000, 500)).toBeNull();
  });

  it('returns null for an unknown OpenAI model', () => {
    expect(estimateCostUsd('openai', 'gpt-3.5-turbo', 1000, 500)).toBeNull();
  });

  it('returns null for a completely unknown provider', () => {
    expect(estimateCostUsd('mistral', 'mistral-large', 1000, 500)).toBeNull();
  });

  // -------------------------------------------------------------------------
  // Zero tokens
  // -------------------------------------------------------------------------

  it('returns 0 for zero tokens on a known model', () => {
    expect(estimateCostUsd('anthropic', 'claude-sonnet-4-20250514', 0, 0)).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Fractional token counts (small usage)
  // -------------------------------------------------------------------------

  it('computes fractional costs for small token counts', () => {
    const cost = estimateCostUsd('anthropic', 'claude-sonnet-4-20250514', 100, 50);
    // (100/1M)*3.00 + (50/1M)*15.00 = 0.0003 + 0.00075 = 0.00105
    expect(cost).toBeCloseTo(0.00105, 6);
  });
});

describe('estimateCostUsd — Bedrock-prefixed Claude model ids', () => {
  it('resolves anthropic.<model> to the matching Claude pricing', () => {
    const cost = estimateCostUsd('anthropic', 'anthropic.claude-sonnet-4-20250514-v1:0', 1_000_000, 1_000_000);
    expect(cost).toBeCloseTo(18.0, 2);
  });

  it('resolves a region inference-profile prefix (us.anthropic.<model>)', () => {
    const cost = estimateCostUsd('anthropic', 'us.anthropic.claude-opus-4-v1:0', 1_000_000, 1_000_000);
    expect(cost).toBeCloseTo(90.0, 2);
  });

  it('still resolves a bare claude-* id unchanged', () => {
    const cost = estimateCostUsd('anthropic', 'claude-haiku-4-20250514', 1_000_000, 1_000_000);
    expect(cost).toBeCloseTo(4.8, 2);
  });
});
