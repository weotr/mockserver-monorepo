import { describe, it, expect, beforeEach } from 'vitest';
import {
  validateRegex,
  matchesRegex,
  loadPresets,
  savePresets,
  upsertPreset,
  deletePreset,
  type FilterPreset,
} from '../lib/filterPresets';

function makePreset(overrides: Partial<FilterPreset> = {}): FilterPreset {
  return {
    name: 'preset',
    method: '',
    path: '',
    body: '',
    secure: false,
    keepAlive: false,
    regex: false,
    headers: [],
    queryStringParameters: [],
    cookies: [],
    actionTypeFilter: [],
    llmProviderFilter: [],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Regex matching
// ---------------------------------------------------------------------------

describe('validateRegex', () => {
  it('treats an empty pattern as valid with no compiled regex', () => {
    const result = validateRegex('');
    expect(result.valid).toBe(true);
    expect(result.regex).toBeUndefined();
  });

  it('compiles a valid pattern', () => {
    const result = validateRegex('/api/.*');
    expect(result.valid).toBe(true);
    expect(result.regex).toBeInstanceOf(RegExp);
  });

  it('reports an error for an invalid pattern without throwing', () => {
    const result = validateRegex('/api/(');
    expect(result.valid).toBe(false);
    expect(result.error).toBeTruthy();
    expect(result.regex).toBeUndefined();
  });
});

describe('matchesRegex', () => {
  const paths = ['/api/users', '/api/orders/42', '/health', '/v2/api/users'];

  it('matches the right path entries with a regex', () => {
    const matched = paths.filter((p) => matchesRegex('^/api/.*', p));
    expect(matched).toEqual(['/api/users', '/api/orders/42']);
  });

  it('matches a numeric segment with a character class', () => {
    const matched = paths.filter((p) => matchesRegex('/orders/[0-9]+$', p));
    expect(matched).toEqual(['/api/orders/42']);
  });

  it('does not throw and matches nothing for an invalid regex', () => {
    expect(() => paths.filter((p) => matchesRegex('/api/(', p))).not.toThrow();
    expect(paths.filter((p) => matchesRegex('/api/(', p))).toEqual([]);
  });

  it('treats an empty pattern as no constraint (matches all)', () => {
    expect(paths.filter((p) => matchesRegex('', p))).toEqual(paths);
  });
});

// ---------------------------------------------------------------------------
// Saved presets + localStorage persistence
// ---------------------------------------------------------------------------

describe('preset persistence', () => {
  beforeEach(() => {
    globalThis.localStorage?.clear();
  });

  it('returns an empty list when nothing is stored', () => {
    expect(loadPresets()).toEqual([]);
  });

  it('round-trips a saved preset through localStorage', () => {
    const preset = makePreset({
      name: 'API GET',
      method: 'GET',
      path: '^/api/.*',
      regex: true,
      actionTypeFilter: ['httpResponse'],
    });
    savePresets([preset]);

    const loaded = loadPresets();
    expect(loaded).toHaveLength(1);
    expect(loaded[0]).toEqual(preset);
    // Confirm it actually went through the storage layer, not in-memory state.
    expect(globalThis.localStorage.getItem('mockserver-filter-presets')).toContain('API GET');
  });

  it('saving and applying a preset round-trips the filter fields', () => {
    const saved = makePreset({ name: 'secure-posts', method: 'POST', path: '/posts', secure: true });
    const list = upsertPreset([], saved);
    savePresets(list);

    const reloaded = loadPresets();
    const applied = reloaded.find((p) => p.name === 'secure-posts');
    expect(applied).toBeDefined();
    expect(applied!.method).toBe('POST');
    expect(applied!.path).toBe('/posts');
    expect(applied!.secure).toBe(true);
  });

  it('ignores corrupt localStorage entries', () => {
    globalThis.localStorage.setItem('mockserver-filter-presets', '{not valid json');
    expect(loadPresets()).toEqual([]);
  });

  it('drops entries with a missing name', () => {
    globalThis.localStorage.setItem(
      'mockserver-filter-presets',
      JSON.stringify([{ method: 'GET' }, { name: 'ok', method: 'POST' }]),
    );
    const loaded = loadPresets();
    expect(loaded).toHaveLength(1);
    expect(loaded[0]!.name).toBe('ok');
  });
});

describe('upsertPreset', () => {
  it('appends a new preset', () => {
    const result = upsertPreset([], makePreset({ name: 'a' }));
    expect(result.map((p) => p.name)).toEqual(['a']);
  });

  it('overwrites an existing preset of the same name instead of duplicating', () => {
    const initial = [makePreset({ name: 'a', method: 'GET' })];
    const result = upsertPreset(initial, makePreset({ name: 'a', method: 'POST' }));
    expect(result).toHaveLength(1);
    expect(result[0]!.method).toBe('POST');
  });

  it('preserves other presets when overwriting one', () => {
    const initial = [makePreset({ name: 'a' }), makePreset({ name: 'b' })];
    const result = upsertPreset(initial, makePreset({ name: 'a', path: '/x' }));
    expect(result.map((p) => p.name).sort()).toEqual(['a', 'b']);
  });
});

describe('deletePreset', () => {
  it('removes the named preset', () => {
    const initial = [makePreset({ name: 'a' }), makePreset({ name: 'b' })];
    expect(deletePreset(initial, 'a').map((p) => p.name)).toEqual(['b']);
  });

  it('is a no-op when the name is absent', () => {
    const initial = [makePreset({ name: 'a' })];
    expect(deletePreset(initial, 'missing')).toEqual(initial);
  });
});
