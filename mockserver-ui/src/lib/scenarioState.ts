/**
 * Shared scenario-state sorting and turn-grouping utilities.
 *
 * Unifies the duplicated logic from ExpectationPanel and TrafficInspector
 * into a single canonical implementation.
 */
import type { JsonListItem } from '../types';

/**
 * Canonical ordering for scenarioState values:
 * - 'Started' (default first expectation registered with no prior state) always sorts first
 * - 'turn_N' values sort in numeric order
 * - '__done' sinks to the bottom
 * - Anything else sorts near the end alphabetically
 */
export function scenarioStateSortKey(state: string): number {
  if (state === 'Started') return -1;
  const match = /^turn_(\d+)$/.exec(state);
  if (match) return parseInt(match[1]!, 10);
  if (state === '__done') return 999999;
  return 500000; // unknown states sort near the end
}

/**
 * Build a map: expectation `key` -> { position, total } based on grouping by
 * scenarioName and sorting by scenarioState. Only stateful (LLM conversation)
 * expectations participate; the map is empty for non-stateful ones.
 */
export function buildTurnPositionMap(items: JsonListItem[]): Map<string, { position: number; total: number }> {
  const groups = new Map<string, JsonListItem[]>();
  for (const item of items) {
    const scenarioName = item.value['scenarioName'] as string | undefined;
    if (!scenarioName) continue;
    const arr = groups.get(scenarioName) ?? [];
    arr.push(item);
    groups.set(scenarioName, arr);
  }

  const result = new Map<string, { position: number; total: number }>();
  for (const group of groups.values()) {
    if (group.length < 2) continue; // single-state scenarios get no turn-of label
    const sorted = [...group].sort((a, b) => {
      const aState = (a.value['scenarioState'] as string | undefined) ?? '';
      const bState = (b.value['scenarioState'] as string | undefined) ?? '';
      return scenarioStateSortKey(aState) - scenarioStateSortKey(bState);
    });
    sorted.forEach((item, idx) => {
      result.set(item.key, { position: idx + 1, total: sorted.length });
    });
  }
  return result;
}
