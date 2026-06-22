import { describe, it, expect } from 'vitest';
import { scenarioStateSortKey, buildTurnPositionMap } from '../lib/scenarioState';

describe('scenarioStateSortKey', () => {
  it('sorts Started first', () => {
    expect(scenarioStateSortKey('Started')).toBe(-1);
  });

  it('sorts turn_N values numerically', () => {
    expect(scenarioStateSortKey('turn_0')).toBe(0);
    expect(scenarioStateSortKey('turn_1')).toBe(1);
    expect(scenarioStateSortKey('turn_10')).toBe(10);
  });

  it('sorts __done last', () => {
    expect(scenarioStateSortKey('__done')).toBe(999999);
  });

  it('sorts unknown states near the end', () => {
    const key = scenarioStateSortKey('custom_state');
    expect(key).toBeGreaterThan(scenarioStateSortKey('turn_100'));
    expect(key).toBeLessThan(scenarioStateSortKey('__done'));
  });

  it('preserves relative order: Started < turn_1 < turn_2 < __done', () => {
    const keys = ['Started', 'turn_1', 'turn_2', '__done'].map(scenarioStateSortKey);
    for (let i = 1; i < keys.length; i++) {
      expect(keys[i]).toBeGreaterThan(keys[i - 1]!);
    }
  });
});

describe('buildTurnPositionMap', () => {
  it('returns empty map when no stateful expectations', () => {
    const items = [
      { key: 'e1', value: { httpResponse: {} } },
    ];
    expect(buildTurnPositionMap(items).size).toBe(0);
  });

  it('returns empty map for single-state scenarios', () => {
    const items = [
      { key: 'e1', value: { scenarioName: 'conv1', scenarioState: 'Started' } },
    ];
    expect(buildTurnPositionMap(items).size).toBe(0);
  });

  it('assigns positions for multi-state scenarios', () => {
    const items = [
      { key: 'e2', value: { scenarioName: 'conv1', scenarioState: 'turn_1' } },
      { key: 'e1', value: { scenarioName: 'conv1', scenarioState: 'Started' } },
    ];
    const map = buildTurnPositionMap(items);
    expect(map.get('e1')).toEqual({ position: 1, total: 2 });
    expect(map.get('e2')).toEqual({ position: 2, total: 2 });
  });
});
