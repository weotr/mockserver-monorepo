import { describe, it, expect } from 'vitest';
import { mismatchDifferencesToDiffResult } from '../lib/diff';

describe('mismatchDifferencesToDiffResult', () => {
  it('returns an identical/zero result for undefined or empty differences', () => {
    expect(mismatchDifferencesToDiffResult(undefined)).toEqual({
      diffCount: 0,
      identical: true,
      diffs: [],
    });
    expect(mismatchDifferencesToDiffResult({})).toEqual({
      diffCount: 0,
      identical: true,
      diffs: [],
    });
  });

  it('parses "expected X but was Y" reasons into expected/actual CHANGED rows', () => {
    const result = mismatchDifferencesToDiffResult({
      path: ['expected /api/users but was /api/items'],
      method: ['expected POST but was GET'],
    });
    expect(result.identical).toBe(false);
    expect(result.diffCount).toBe(2);
    expect(result.diffs).toContainEqual({
      field: 'path',
      expectedValue: '/api/users',
      actualValue: '/api/items',
      diffType: 'CHANGED',
    });
    expect(result.diffs).toContainEqual({
      field: 'method',
      expectedValue: 'POST',
      actualValue: 'GET',
      diffType: 'CHANGED',
    });
  });

  it('keeps the raw reason text when it does not match the expected/was pattern', () => {
    const result = mismatchDifferencesToDiffResult({
      body: ['no JSON body was present'],
    });
    expect(result.diffs).toEqual([
      {
        field: 'body',
        expectedValue: 'no JSON body was present',
        actualValue: undefined,
        diffType: 'CHANGED',
      },
    ]);
  });

  it('emits one row per reason for a field with multiple differences', () => {
    const result = mismatchDifferencesToDiffResult({
      headers: [
        'expected accept but was missing',
        'expected application/json but was text/plain',
      ],
    });
    expect(result.diffCount).toBe(2);
    expect(result.diffs.every((d) => d.field === 'headers')).toBe(true);
  });

  it('parses multi-line / whitespace-heavy body reasons', () => {
    const result = mismatchDifferencesToDiffResult({
      body: ['expected {\n  "a": 1\n} but was {\n  "a": 2\n}'],
    });
    expect(result.diffs[0]).toEqual({
      field: 'body',
      expectedValue: '{\n  "a": 1\n}',
      actualValue: '{\n  "a": 2\n}',
      diffType: 'CHANGED',
    });
  });
});
