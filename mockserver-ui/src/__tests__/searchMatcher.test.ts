import { describe, it, expect } from 'vitest';
import { matchesItemSearch, matchesLogSearch, extractSearchableFields, parseSearchTerm } from '../lib/searchMatcher';

describe('extractSearchableFields', () => {
  it('extracts top-level id and description', () => {
    const fields = extractSearchableFields({ id: 'exp-1', description: 'My expectation' });
    expect(fields).toContain('exp-1');
    expect(fields).toContain('My expectation');
  });

  it('extracts httpRequest method and path', () => {
    const fields = extractSearchableFields({ httpRequest: { method: 'POST', path: '/api/users' } });
    expect(fields).toContain('POST');
    expect(fields).toContain('/api/users');
  });

  it('extracts httpResponse statusCode', () => {
    const fields = extractSearchableFields({ httpResponse: { statusCode: 404 } });
    expect(fields).toContain('404');
  });

  it('extracts LLM provider and model', () => {
    const fields = extractSearchableFields({
      httpLlmResponse: { provider: 'ANTHROPIC', model: 'claude-3-sonnet' },
    });
    expect(fields).toContain('ANTHROPIC');
    expect(fields).toContain('claude-3-sonnet');
  });

  it('includes action type key', () => {
    const fields = extractSearchableFields({ httpLlmResponse: { provider: 'OPENAI' } });
    expect(fields).toContain('httpLlmResponse');
  });
});

describe('matchesItemSearch', () => {
  it('matches on extracted fields (method)', () => {
    expect(matchesItemSearch({ httpRequest: { method: 'POST', path: '/api' } }, 'POST')).toBe(true);
  });

  it('matches case-insensitively', () => {
    expect(matchesItemSearch({ httpRequest: { method: 'GET', path: '/api' } }, 'get')).toBe(true);
  });

  it('does not false-match on structural keys like "value"', () => {
    // Searching "value" should not match just because JSON.stringify contains the key "value"
    // unless the actual content contains "value"
    const item = { httpRequest: { method: 'GET', path: '/api/items' } };
    // "value" appears as a JSON key in stringify but not in field content
    const fields = extractSearchableFields(item);
    expect(fields.some(f => f.toLowerCase().includes('value'))).toBe(false);
  });

  it('falls back to JSON stringify for deep nested values', () => {
    expect(matchesItemSearch({ httpRequest: { headers: [{ name: 'X-Custom', values: ['special-token-123'] }] } }, 'special-token-123')).toBe(true);
  });
});

describe('matchesLogSearch', () => {
  it('matches on key', () => {
    expect(matchesLogSearch({ key: 'log-entry-1', value: {} }, 'log-entry')).toBe(true);
  });

  it('matches on log value message field', () => {
    expect(matchesLogSearch({ key: 'k', value: { message: 'Request matched expectation' } }, 'matched')).toBe(true);
  });

  it('falls back to JSON for deep content', () => {
    expect(matchesLogSearch({ key: 'k', value: { nested: { deep: 'findme' } } }, 'findme')).toBe(true);
  });

  it('supports regex terms wrapped in slashes', () => {
    expect(matchesLogSearch({ key: 'k', value: { message: 'GET /api/users' } }, '/get .*users/')).toBe(true);
    expect(matchesLogSearch({ key: 'k', value: { message: 'POST /api/users' } }, '/^get/')).toBe(false);
  });

  it('returns false for an operator-only term (logs have no request fields)', () => {
    expect(matchesLogSearch({ key: 'k', value: { message: 'anything' } }, 'status:>=400')).toBe(false);
  });
});

describe('parseSearchTerm', () => {
  it('splits field operators from free text', () => {
    const parsed = parseSearchTerm('status:>=400 method:POST hello world');
    expect(parsed.operators).toEqual([
      { field: 'status', comparator: '>=', expr: '400' },
      { field: 'method', expr: 'POST' },
    ]);
    expect(parsed.text).toBe('hello world');
  });

  it('leaves unknown field-like tokens (e.g. URLs) as free text', () => {
    const parsed = parseSearchTerm('http://example.com/path');
    expect(parsed.operators).toHaveLength(0);
    expect(parsed.text).toBe('http://example.com/path');
  });
});

describe('matchesItemSearch — regex and field operators', () => {
  const item = {
    httpRequest: { method: 'POST', path: '/api/users/42' },
    httpResponse: { statusCode: 503 },
  };

  it('matches a numeric status comparison', () => {
    expect(matchesItemSearch(item, 'status:>=500')).toBe(true);
    expect(matchesItemSearch(item, 'status:>=600')).toBe(false);
    expect(matchesItemSearch(item, 'status:<400')).toBe(false);
  });

  it('matches an exact status equality', () => {
    expect(matchesItemSearch(item, 'status:503')).toBe(true);
    expect(matchesItemSearch(item, 'status:200')).toBe(false);
  });

  it('matches method case-insensitively', () => {
    expect(matchesItemSearch(item, 'method:post')).toBe(true);
    expect(matchesItemSearch(item, 'method:GET')).toBe(false);
  });

  it('matches a path glob', () => {
    expect(matchesItemSearch(item, 'path:/api/*')).toBe(true);
    expect(matchesItemSearch(item, 'path:/api/users/*')).toBe(true);
    expect(matchesItemSearch(item, 'path:/other/*')).toBe(false);
  });

  it('ANDs multiple operators together', () => {
    expect(matchesItemSearch(item, 'method:POST status:>=500')).toBe(true);
    expect(matchesItemSearch(item, 'method:GET status:>=500')).toBe(false);
  });

  it('ANDs operators with free text', () => {
    expect(matchesItemSearch(item, 'status:503 users')).toBe(true);
    expect(matchesItemSearch(item, 'status:503 missingword')).toBe(false);
  });

  it('matches a regex free-text term', () => {
    expect(matchesItemSearch(item, '/users\\/\\d+/')).toBe(true);
    expect(matchesItemSearch(item, '/^nope/')).toBe(false);
  });

  it('treats an invalid regex as matching nothing rather than throwing', () => {
    expect(matchesItemSearch(item, '/[unclosed/')).toBe(false);
  });
});
