import { useMemo } from 'react';
import Typography from '@mui/material/Typography';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';
import Panel from './Panel';
import JsonListItemComponent from './JsonListItem';
import { applyClientFilters } from '../lib/clientFilters';

function matchesSearch(item: JsonListItem, term: string): boolean {
  return JSON.stringify(item).toLowerCase().includes(term.toLowerCase());
}

// Canonical ordering for scenarioState values: 'Started' (default first
// expectation registered with no prior state) always sorts first, followed
// by 'turn_N' values in numeric order, then anything else alphabetically.
function scenarioStateSortKey(state: string): number {
  if (state === 'Started') return -1;
  const m = /^turn_(\d+)$/.exec(state);
  if (m) return parseInt(m[1]!, 10);
  return 1_000_000; // unknown states sink to the bottom
}

/**
 * Build a map: expectation `key` -> { position, total } based on grouping by
 * scenarioName and sorting by scenarioState. Only stateful (LLM conversation)
 * expectations participate; the map is empty for non-stateful ones.
 */
function buildTurnPositionMap(items: JsonListItem[]): Map<string, { position: number; total: number }> {
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

export default function ExpectationPanel() {
  const expectations = useDashboardStore((s) => s.activeExpectations);
  const search = useDashboardStore((s) => s.expectationSearch);
  const setSearch = useDashboardStore((s) => s.setExpectationSearch);
  const filterEnabled = useDashboardStore((s) => s.filterEnabled);
  const actionTypeFilter = useDashboardStore((s) => s.actionTypeFilter);
  const llmProviderFilter = useDashboardStore((s) => s.llmProviderFilter);

  // Compute turn N of M across the FULL set (not the filtered subset) so the
  // total stays meaningful even when search hides siblings.
  const turnPositions = useMemo(() => buildTurnPositionMap(expectations), [expectations]);

  const clientFiltered = useMemo(
    () => filterEnabled ? applyClientFilters(expectations, actionTypeFilter, llmProviderFilter) : expectations,
    [expectations, filterEnabled, actionTypeFilter, llmProviderFilter],
  );

  const filtered = useMemo(
    () => (search ? clientFiltered.filter((e) => matchesSearch(e, search)) : clientFiltered),
    [clientFiltered, search],
  );

  return (
    <Panel
      title="Active Expectations"
      count={expectations.length}
      searchValue={search}
      onSearchChange={setSearch}
    >
      {filtered.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
          {expectations.length === 0
            ? 'No active expectations — add one in the Mocks tab, or import an OpenAPI/WSDL spec from the tools menu.'
            : 'No matching expectations'}
        </Typography>
      ) : (
        filtered.map((item, index) => (
          <JsonListItemComponent
            key={item.key}
            item={item}
            index={index + 1}
            turnPosition={turnPositions.get(item.key)}
          />
        ))
      )}
    </Panel>
  );
}
