/**
 * Helpers for the request-log FilterPanel's richer filtering features (WS5.4):
 *
 *  1. Regex matching for the path / method fields. The panel lets the user opt
 *     in to regex with a small toggle. When regex is on we validate the pattern
 *     client-side (so an invalid pattern shows a subtle error instead of being
 *     silently shipped to the server) and we match log entries locally so the
 *     behaviour is identical regardless of how the server interprets the value.
 *
 *  2. Saved filter presets — save the current filter set under a name, re-apply
 *     it later, delete it. Persisted to localStorage with the same defensive
 *     try/catch pattern the theme uses (see store/index.ts).
 */

import type { RequestFilter } from '../types';

// ---------------------------------------------------------------------------
// Regex matching
// ---------------------------------------------------------------------------

export interface RegexValidation {
  valid: boolean;
  /** Compiled matcher, present only when `valid` is true. */
  regex?: RegExp;
  /** Human-readable reason, present only when `valid` is false. */
  error?: string;
}

/**
 * Validate (and compile) a user-supplied regex pattern. Never throws — an
 * invalid pattern returns `{ valid: false, error }` so the caller can show a
 * subtle error state rather than crashing on every keystroke.
 *
 * An empty pattern is treated as valid-but-absent (no regex), matching the
 * existing "blank field = no constraint" semantics.
 */
export function validateRegex(pattern: string): RegexValidation {
  if (pattern === '') return { valid: true };
  try {
    return { valid: true, regex: new RegExp(pattern) };
  } catch (e) {
    return { valid: false, error: e instanceof Error ? e.message : 'Invalid regular expression' };
  }
}

/**
 * Test a value against a user-supplied regex pattern. Returns false for an
 * invalid pattern (so a half-typed regex matches nothing rather than throwing),
 * and true for an empty pattern (no constraint).
 */
export function matchesRegex(pattern: string, value: string): boolean {
  if (pattern === '') return true;
  const { valid, regex } = validateRegex(pattern);
  if (!valid || !regex) return false;
  return regex.test(value);
}

// ---------------------------------------------------------------------------
// Saved filter presets
// ---------------------------------------------------------------------------

/**
 * The complete, re-applicable state of the FilterPanel. This is a superset of
 * the wire-level `RequestFilter` because it also captures UI-only dimensions
 * (the action-type / LLM-provider chip clusters and the regex toggle) so a
 * preset round-trips the whole panel, not just what gets sent to the server.
 */
export interface FilterPreset {
  name: string;
  method: string;
  path: string;
  /** Request-body substring filter (optional; defaults to '' for older presets). */
  body: string;
  secure: boolean;
  keepAlive: boolean;
  regex: boolean;
  headers: RequestFilter['headers'];
  queryStringParameters: RequestFilter['queryStringParameters'];
  cookies: RequestFilter['cookies'];
  actionTypeFilter: string[];
  llmProviderFilter: string[];
}

const PRESETS_STORAGE_KEY = 'mockserver-filter-presets';

function isStringArray(v: unknown): v is string[] {
  return Array.isArray(v) && v.every((x) => typeof x === 'string');
}

/**
 * Validate one parsed preset. Returns the normalised preset or null if the
 * shape is wrong, so a corrupt/poisoned localStorage entry cannot break the
 * panel. Unknown extra keys are ignored; missing optional collections default
 * to empty.
 */
function coercePreset(raw: unknown): FilterPreset | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r['name'] !== 'string' || r['name'] === '') return null;
  return {
    name: r['name'],
    method: typeof r['method'] === 'string' ? r['method'] : '',
    path: typeof r['path'] === 'string' ? r['path'] : '',
    body: typeof r['body'] === 'string' ? r['body'] : '',
    secure: r['secure'] === true,
    keepAlive: r['keepAlive'] === true,
    regex: r['regex'] === true,
    headers: Array.isArray(r['headers']) ? (r['headers'] as FilterPreset['headers']) : [],
    queryStringParameters: Array.isArray(r['queryStringParameters'])
      ? (r['queryStringParameters'] as FilterPreset['queryStringParameters'])
      : [],
    cookies: Array.isArray(r['cookies']) ? (r['cookies'] as FilterPreset['cookies']) : [],
    actionTypeFilter: isStringArray(r['actionTypeFilter']) ? r['actionTypeFilter'] : [],
    llmProviderFilter: isStringArray(r['llmProviderFilter']) ? r['llmProviderFilter'] : [],
  };
}

/** Load all saved presets from localStorage. Never throws; returns [] on any problem. */
export function loadPresets(): FilterPreset[] {
  try {
    const stored = globalThis.localStorage?.getItem(PRESETS_STORAGE_KEY);
    if (!stored) return [];
    const parsed = JSON.parse(stored);
    if (!Array.isArray(parsed)) return [];
    return parsed.map(coercePreset).filter((p): p is FilterPreset => p !== null);
  } catch {
    return [];
  }
}

/** Persist the given presets to localStorage. Never throws. */
export function savePresets(presets: FilterPreset[]): void {
  try {
    globalThis.localStorage?.setItem(PRESETS_STORAGE_KEY, JSON.stringify(presets));
  } catch {
    /* localStorage may be unavailable / full; saving is best-effort */
  }
}

/**
 * Upsert a preset by name (case-sensitive). Returns the new list (saving an
 * existing name overwrites it rather than duplicating). The returned list is a
 * fresh array suitable for React state.
 */
export function upsertPreset(presets: FilterPreset[], preset: FilterPreset): FilterPreset[] {
  const without = presets.filter((p) => p.name !== preset.name);
  return [...without, preset];
}

/** Remove a preset by name. Returns the new list. */
export function deletePreset(presets: FilterPreset[], name: string): FilterPreset[] {
  return presets.filter((p) => p.name !== name);
}
