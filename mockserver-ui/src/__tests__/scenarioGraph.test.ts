import { describe, it, expect } from 'vitest';
import {
  buildScenarioGraphModel,
  toScenarioMermaid,
  buildStateIdMap,
  sanitizeStateLabel,
} from '../lib/scenarioGraph';

describe('scenarioGraph — model building', () => {
  it('folds the current state, observed states and transition endpoints into the state set', () => {
    const model = buildScenarioGraphModel(
      ['started'],
      [{ from: 'started', to: 'paid' }],
      'paid',
    );
    // current state first, then observed, then transition endpoints — de-duplicated.
    expect(model.states).toEqual(['paid', 'started']);
    expect(model.transitions).toEqual([{ from: 'started', to: 'paid' }]);
    expect(model.currentState).toBe('paid');
  });

  it('de-duplicates repeated transitions and ignores self / empty transitions', () => {
    const model = buildScenarioGraphModel(
      [],
      [
        { from: 'a', to: 'b' },
        { from: 'a', to: 'b' },
        { from: 'x', to: 'x' },
        { from: '', to: 'b' },
        { from: 'b', to: '  ' },
      ],
      null,
    );
    expect(model.transitions).toEqual([{ from: 'a', to: 'b' }]);
  });

  it('trims whitespace and skips blank states', () => {
    const model = buildScenarioGraphModel(['  ', ' open '], [], ' open ');
    expect(model.states).toEqual(['open']);
  });
});

describe('scenarioGraph — id mapping and label sanitisation', () => {
  it('maps unsafe state names to safe ids and disambiguates collisions', () => {
    const ids = buildStateIdMap(['order placed', 'order-placed', '123', '']);
    expect(ids.get('order placed')).toBe('order_placed');
    // sanitises to the same base -> gets a counter suffix.
    expect(ids.get('order-placed')).toBe('order_placed_1');
    // leading digit / empty get a prefix so the id is a valid identifier.
    expect(ids.get('123')).toBe('s_123');
    expect(ids.get('')).toBe('s_');
  });

  it('never emits HTML and strips quotes/newlines from labels', () => {
    const label = sanitizeStateLabel('paid\n<br/>"now"');
    expect(label).not.toContain('<');
    expect(label).not.toContain('\n');
    expect(label).not.toContain('"');
  });
});

describe('scenarioGraph — Mermaid rendering', () => {
  it('returns an empty string when there are no states', () => {
    expect(toScenarioMermaid(buildScenarioGraphModel([], [], null))).toBe('');
  });

  it('builds a stateDiagram-v2 with states, transitions and a highlighted current state', () => {
    const model = buildScenarioGraphModel(
      ['started'],
      [{ from: 'started', to: 'paid' }],
      'paid',
    );
    const src = toScenarioMermaid(model);

    expect(src.startsWith('stateDiagram-v2')).toBe(true);
    // state declarations carry the human-readable description (unquoted — the
    // text after `:` in stateDiagram-v2 is a description, not a quoted string).
    expect(src).toContain('started : started');
    expect(src).toContain('paid : paid');
    // initial marker points at the live current state.
    expect(src).toContain('[*] --> paid');
    // the transition edge is drawn.
    expect(src).toContain('started --> paid');
    // the current state is highlighted via a classDef.
    expect(src).toContain('classDef current');
    expect(src).toContain('class paid current');
    // never any HTML tags in the diagram.
    expect(src).not.toContain('<br');
    expect(src).not.toContain('<');
  });

  it('sanitises state names that would break Mermaid syntax into safe ids', () => {
    const model = buildScenarioGraphModel(
      ['order placed'],
      [{ from: 'order placed', to: 'order shipped' }],
      'order placed',
    );
    const src = toScenarioMermaid(model);
    // ids are identifier-safe; the original name survives in the description.
    expect(src).toContain('order_placed : order placed');
    expect(src).toContain('order_placed --> order_shipped');
    expect(src).toContain('class order_placed current');
  });
});
