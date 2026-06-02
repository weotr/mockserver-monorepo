/**
 * Estimated cost calculation for LLM API usage.
 *
 * Pure function that consults a pricing table keyed on (provider, model)
 * with inputPerMillion and outputPerMillion rates in USD.
 *
 * Returns null for unknown models so the UI can display "—".
 *
 * Pricing source / freshness
 * --------------------------
 * Rates below are public provider list prices captured 2025-Q4. They WILL
 * drift; treat the dashboard total as an estimate, not an invoice.
 * Refresh procedure: update the relevant `*_PRICING` array entry and
 * append the source URL + capture date to its inline comment. There is
 * no automated drift check — this table is a maintenance burden.
 *
 * Sources:
 *   - Anthropic: https://www.anthropic.com/pricing
 *   - OpenAI:    https://openai.com/api/pricing
 *   - Gemini:    https://ai.google.dev/pricing
 */

// ---------------------------------------------------------------------------
// Pricing table
// ---------------------------------------------------------------------------

interface PricingEntry {
  inputPerMillion: number;
  outputPerMillion: number;
}

/**
 * Pricing entries keyed by a model-name prefix. The lookup walks this list
 * in order and uses the first entry whose key is a prefix of the model id
 * (case-insensitive). More-specific prefixes must appear before less-specific
 * ones so that e.g. "claude-opus-4" matches before a hypothetical "claude-" catch-all.
 */
const ANTHROPIC_PRICING: Array<[string, PricingEntry]> = [
  ['claude-opus-4', { inputPerMillion: 15.0, outputPerMillion: 75.0 }],
  ['claude-sonnet-4', { inputPerMillion: 3.0, outputPerMillion: 15.0 }],
  ['claude-haiku-4', { inputPerMillion: 0.8, outputPerMillion: 4.0 }],
];

const OPENAI_PRICING: Array<[string, PricingEntry]> = [
  // ORDERING MATTERS — the lookup uses startsWith, so the longer
  // `gpt-4o-mini` MUST appear before `gpt-4o`. Reversing the order would
  // cause every `gpt-4o-mini` model to silently bill at gpt-4o's rate.
  ['gpt-4o-mini', { inputPerMillion: 0.15, outputPerMillion: 0.6 }],
  ['gpt-4o', { inputPerMillion: 2.5, outputPerMillion: 10.0 }],
  ['o3', { inputPerMillion: 15.0, outputPerMillion: 60.0 }],
];

const GEMINI_PRICING: Array<[string, PricingEntry]> = [
  ['gemini-2.0-flash', { inputPerMillion: 0.1, outputPerMillion: 0.4 }],
];

// Ollama is always free (local models)
const OLLAMA_PRICING: PricingEntry = { inputPerMillion: 0, outputPerMillion: 0 };

// ---------------------------------------------------------------------------
// Lookup helpers
// ---------------------------------------------------------------------------

function findPricing(table: Array<[string, PricingEntry]>, model: string): PricingEntry | null {
  const lower = model.toLowerCase();
  for (const [prefix, entry] of table) {
    if (lower.startsWith(prefix.toLowerCase())) {
      return entry;
    }
  }
  return null;
}

/**
 * Look up pricing for a given provider and model.
 * Returns null if the model is not recognised.
 */
function getPricing(provider: string, model: string): PricingEntry | null {
  const kind = provider.toLowerCase();

  if (kind === 'anthropic' || kind === 'bedrock') {
    // parseTraffic classifies Bedrock Claude traffic as kind 'anthropic', and its model
    // ids are prefixed (e.g. "anthropic.claude-sonnet-4-…" or, with inference profiles,
    // "us.anthropic.claude-…"). Strip up to and including "anthropic." so the claude-*
    // pricing keys match; a bare "claude-…" id is unaffected.
    const stripped = model.replace(/^.*anthropic\./, '');
    return findPricing(ANTHROPIC_PRICING, stripped);
  }
  if (kind === 'openai' || kind === 'azure_openai') {
    // Azure OpenAI traffic uses *deployment names* (user-defined, e.g.
    // "my-gpt4o-prod") rather than canonical OpenAI model ids, so this
    // lookup will return null for most real Azure deployments. The UI
    // surfaces this as "—" — document that limitation if you wire an
    // Azure-detection path into `parseTraffic`.
    return findPricing(OPENAI_PRICING, model);
  }
  if (kind === 'openai_responses') {
    return findPricing(OPENAI_PRICING, model);
  }
  if (kind === 'gemini') {
    return findPricing(GEMINI_PRICING, model);
  }
  if (kind === 'ollama') {
    return OLLAMA_PRICING;
  }

  return null;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Estimate the cost in USD for a given provider, model, and token counts.
 *
 * @returns The estimated cost in USD, or `null` if the model is unknown.
 *          Returns `0` for zero tokens even on a known model.
 */
export function estimateCostUsd(
  provider: string,
  model: string,
  inputTokens: number,
  outputTokens: number,
): number | null {
  const pricing = getPricing(provider, model);
  if (pricing === null) return null;

  if (inputTokens === 0 && outputTokens === 0) return 0;

  return (
    (inputTokens / 1_000_000) * pricing.inputPerMillion +
    (outputTokens / 1_000_000) * pricing.outputPerMillion
  );
}
