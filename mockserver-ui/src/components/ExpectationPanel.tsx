import { useMemo, useRef, useState, useCallback } from 'react';
import Typography from '@mui/material/Typography';
import Tooltip from '@mui/material/Tooltip';
import ToggleButton from '@mui/material/ToggleButton';
import SortIcon from '@mui/icons-material/Sort';
import { useDashboardStore } from '../store';
import Panel from './Panel';
import JsonListItemComponent, { extractPriority } from './JsonListItem';
import ProgressiveList from './ProgressiveList';
import ConfirmDialog from './ConfirmDialog';
import { useExpansion } from '../hooks/useExpansion';
import { useConnectionParams } from '../hooks/useConnectionParams';
import { applyClientFilters } from '../lib/clientFilters';
import { matchesItemSearch } from '../lib/searchMatcher';
import { buildTurnPositionMap } from '../lib/scenarioState';
import { deleteExpectation } from '../lib/expectations';
import { humanizeError } from '../lib/errorMessage';
import type { JsonListItem } from '../types';

/** Pull the expectation id out of a row value, when present. */
function expectationIdOf(item: JsonListItem): string | null {
  const id = item.value['id'];
  return typeof id === 'string' ? id : null;
}

/**
 * Build a duplicate of an expectation suitable for the Composer's create flow:
 * a deep-ish copy of the value with its `id` removed so that saving it through
 * the normal create path mints a brand-new expectation rather than overwriting
 * the original. Priority and every other field are preserved. Purely
 * client-side — no backend call.
 */
export function duplicateValueWithoutId(value: Record<string, unknown>): Record<string, unknown> {
  const copy: Record<string, unknown> = structuredClone(value);
  delete copy['id'];
  return copy;
}

export default function ExpectationPanel() {
  const params = useConnectionParams();
  const expectations = useDashboardStore((s) => s.activeExpectations);
  const search = useDashboardStore((s) => s.expectationSearch);
  const setSearch = useDashboardStore((s) => s.setExpectationSearch);
  const filterEnabled = useDashboardStore((s) => s.filterEnabled);
  const actionTypeFilter = useDashboardStore((s) => s.actionTypeFilter);
  const llmProviderFilter = useDashboardStore((s) => s.llmProviderFilter);
  const setNotification = useDashboardStore((s) => s.setNotification);
  const editExpectation = useDashboardStore((s) => s.editExpectation);

  // Pending single-expectation delete, awaiting confirmation. Null when no
  // delete is in flight.
  const [pendingDelete, setPendingDelete] = useState<JsonListItem | null>(null);

  // When true, rows are sorted by match priority (descending), which mirrors
  // the order MockServer evaluates equally-specific mocks. Off by default so
  // the list keeps its natural (insertion) order.
  const [sortByPriority, setSortByPriority] = useState(false);

  // Guards the confirm handler against re-entrancy: the ConfirmDialog button
  // fires onConfirm() before the close re-render lands, so a fast double-click
  // could otherwise dispatch two DELETE requests for the same expectation.
  const deletingRef = useRef(false);

  // Compute turn N of M across the FULL set (not the filtered subset) so the
  // total stays meaningful even when search hides siblings.
  const turnPositions = useMemo(() => buildTurnPositionMap(expectations), [expectations]);

  const clientFiltered = useMemo(
    () => filterEnabled ? applyClientFilters(expectations, actionTypeFilter, llmProviderFilter) : expectations,
    [expectations, filterEnabled, actionTypeFilter, llmProviderFilter],
  );

  const searched = useMemo(
    () => (search ? clientFiltered.filter((e) => matchesItemSearch(e.value, search)) : clientFiltered),
    [clientFiltered, search],
  );

  // When priority sorting is on, order by priority descending (higher wins =
  // matched first). Use a stable sort over a copy and treat a missing priority
  // as 0 so non-expectation-shaped rows don't jump around. Insertion order is
  // preserved when the toggle is off.
  const filtered = useMemo(() => {
    if (!sortByPriority) return searched;
    return [...searched].sort(
      (a, b) => (extractPriority(b.value) ?? 0) - (extractPriority(a.value) ?? 0),
    );
  }, [searched, sortByPriority]);

  const expansion = useExpansion();

  const handleEdit = useCallback(
    (item: JsonListItem) => {
      editExpectation(item.value);
    },
    [editExpectation],
  );

  // Duplicate: load a COPY of the expectation into the Composer with its id
  // stripped so saving creates a new expectation. Reuses the same Composer
  // hand-off as Edit; the id-less value makes the Composer treat it as a fresh
  // draft rather than an update.
  const handleDuplicate = useCallback(
    (item: JsonListItem) => {
      editExpectation(duplicateValueWithoutId(item.value));
    },
    [editExpectation],
  );

  const handleConfirmDelete = useCallback(async () => {
    if (!pendingDelete) return;
    const id = expectationIdOf(pendingDelete);
    if (!id) return;
    // No-op if a delete is already in flight (re-entrant double-click).
    if (deletingRef.current) return;
    deletingRef.current = true;
    try {
      await deleteExpectation(params, id);
      // Optimistically drop the row; the next WebSocket push will reconcile.
      useDashboardStore.setState((s) => ({
        activeExpectations: s.activeExpectations.filter((e) => e.key !== pendingDelete.key),
      }));
      setNotification({ message: `Expectation ${id} deleted`, severity: 'success' });
    } catch (e) {
      setNotification({ message: humanizeError(e).message, severity: 'error' });
    } finally {
      deletingRef.current = false;
    }
  }, [pendingDelete, params, setNotification]);

  return (
    <>
      <Panel
        title="Active Expectations"
        count={expectations.length}
        filteredCount={filtered.length !== expectations.length ? filtered.length : undefined}
        searchValue={search}
        onSearchChange={setSearch}
        headerActions={
          <Tooltip title="Sort by match priority (highest first)">
            <ToggleButton
              value="priority"
              selected={sortByPriority}
              onChange={() => setSortByPriority((prev) => !prev)}
              size="small"
              aria-label="Sort by priority"
              sx={{ height: 24, px: 0.75, py: 0, textTransform: 'none', gap: 0.25 }}
            >
              <SortIcon sx={{ fontSize: '1rem' }} />
              <Typography variant="caption">Priority</Typography>
            </ToggleButton>
          </Tooltip>
        }
      >
        {filtered.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            {expectations.length === 0
              ? 'No active expectations — add one in the Mocks tab, or import an OpenAPI/WSDL spec from the tools menu.'
              : 'No matching expectations'}
          </Typography>
        ) : (
          <ProgressiveList
            count={filtered.length}
            getKey={(i) => filtered[i]!.key}
            renderRow={(i) => {
              const item = filtered[i]!;
              // Per-row actions are only meaningful for expectations that carry
              // an id (every Active Expectations row does); guard anyway so a
              // malformed row never offers a no-op delete.
              const hasId = expectationIdOf(item) !== null;
              return (
                <JsonListItemComponent
                  item={item}
                  index={i + 1}
                  turnPosition={turnPositions.get(item.key)}
                  expanded={expansion.isExpanded(item.key)}
                  onToggleExpand={expansion.toggle}
                  onEdit={hasId ? handleEdit : undefined}
                  onDuplicate={hasId ? handleDuplicate : undefined}
                  onDelete={hasId ? setPendingDelete : undefined}
                />
              );
            }}
          />
        )}
      </Panel>
      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete this expectation?"
        message={
          pendingDelete
            ? `Remove expectation ${expectationIdOf(pendingDelete) ?? ''} from the server. Recorded requests and logs are kept. This cannot be undone.`
            : ''
        }
        confirmLabel="Delete"
        onConfirm={() => { void handleConfirmDelete(); }}
        onClose={() => setPendingDelete(null)}
      />
    </>
  );
}
