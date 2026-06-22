import { useRef } from 'react';
import Box from '@mui/material/Box';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import { useDashboardStore } from '../store';
import { useDragResize } from '../hooks/useDragResize';
import LogPanel from './LogPanel';
import ExpectationPanel from './ExpectationPanel';
import RequestPanel from './RequestPanel';

// Keep at least 15% / at most 85% of each axis for a panel so no quadrant can be
// dragged to nothing.
const SPLIT_MIN = 0.15;
const SPLIT_MAX = 0.85;

export default function DashboardGrid() {
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const receivedSearch = useDashboardStore((s) => s.receivedSearch);
  const proxiedSearch = useDashboardStore((s) => s.proxiedSearch);
  const setReceivedSearch = useDashboardStore((s) => s.setReceivedSearch);
  const setProxiedSearch = useDashboardStore((s) => s.setProxiedSearch);

  const theme = useTheme();
  // On small screens the hard 2x2 grid squashes each quadrant to an unreadable
  // sliver. Collapse to a single stacked column and let the container scroll
  // vertically (the app root is height:100vh; overflow:hidden) so each panel
  // gets a usable minimum height instead of being crushed.
  const stacked = useMediaQuery(theme.breakpoints.down('md'));

  // The grid container we measure to convert a pointer position into a fraction.
  const gridRef = useRef<HTMLDivElement | null>(null);

  // Column split (vertical divider, drag changes WIDTH) and row split
  // (horizontal divider, drag changes HEIGHT) — both persisted as fractions.
  const col = useDragResize({
    orientation: 'vertical',
    initial: 0.5,
    min: SPLIT_MIN,
    max: SPLIT_MAX,
    storageKey: 'mockserver-dashboard-grid-col',
    ariaLabel: 'Resize dashboard columns',
    computeFromPointer: (event) => {
      const rect = gridRef.current?.getBoundingClientRect();
      if (!rect || rect.width === 0) return 0.5;
      return (event.clientX - rect.left) / rect.width;
    },
  });
  const row = useDragResize({
    orientation: 'horizontal',
    initial: 0.5,
    min: SPLIT_MIN,
    max: SPLIT_MAX,
    storageKey: 'mockserver-dashboard-grid-row',
    ariaLabel: 'Resize dashboard rows',
    computeFromPointer: (event) => {
      const rect = gridRef.current?.getBoundingClientRect();
      if (!rect || rect.height === 0) return 0.5;
      return (event.clientY - rect.top) / rect.height;
    },
  });

  // Subtle splitter styling: transparent at rest, accent on hover/focus/active,
  // with a ~6px hit area sitting in the grid gap.
  const handleBase = {
    position: 'absolute' as const,
    zIndex: 2,
    backgroundColor: 'transparent',
    transition: theme.transitions.create('background-color', {
      duration: theme.transitions.duration.shorter,
    }),
    '&:hover, &:focus-visible, &:active': {
      backgroundColor: theme.palette.primary.main,
      opacity: 0.55,
    },
    '&:focus-visible': {
      outline: 'none',
    },
  };

  if (stacked) {
    return (
      <Box
        sx={{
          flex: 1,
          display: 'grid',
          gridTemplateColumns: '1fr',
          // Auto-size rows to a usable minimum and scroll the whole grid.
          gridAutoRows: 'minmax(320px, auto)',
          gap: 1,
          p: 1,
          overflowY: 'auto',
          overflowX: 'hidden',
          minHeight: 0,
        }}
      >
        <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
          <LogPanel />
        </Box>
        <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
          <ExpectationPanel />
        </Box>
        <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
          <RequestPanel
            title="Received Requests"
            items={recordedRequests}
            searchValue={receivedSearch}
            onSearchChange={setReceivedSearch}
          />
        </Box>
        <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
          <RequestPanel
            title="Proxied Requests"
            items={proxiedRequests}
            searchValue={proxiedSearch}
            onSearchChange={setProxiedSearch}
          />
        </Box>
      </Box>
    );
  }

  const colPct = `${col.value * 100}%`;
  const rowPct = `${row.value * 100}%`;

  return (
    <Box
      ref={gridRef}
      sx={{
        flex: 1,
        position: 'relative',
        display: 'grid',
        gridTemplateColumns: `${col.value}fr ${1 - col.value}fr`,
        gridTemplateRows: `${row.value}fr ${1 - row.value}fr`,
        gap: 1,
        p: 1,
        overflow: 'hidden',
        minHeight: 0,
      }}
    >
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <LogPanel />
      </Box>
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <ExpectationPanel />
      </Box>
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <RequestPanel
          title="Received Requests"
          items={recordedRequests}
          searchValue={receivedSearch}
          onSearchChange={setReceivedSearch}
        />
      </Box>
      <Box sx={{ minHeight: 0, overflow: 'hidden' }}>
        <RequestPanel
          title="Proxied Requests"
          items={proxiedRequests}
          searchValue={proxiedSearch}
          onSearchChange={setProxiedSearch}
        />
      </Box>

      {/* Vertical divider at the column boundary (spans both rows). */}
      <Box
        data-testid="dashboard-col-resizer"
        {...col.getHandleProps()}
        sx={{
          ...handleBase,
          top: 0,
          bottom: 0,
          left: colPct,
          width: 8,
          transform: 'translateX(-50%)',
          cursor: 'col-resize',
        }}
      />
      {/* Horizontal divider at the row boundary (spans both columns). */}
      <Box
        data-testid="dashboard-row-resizer"
        {...row.getHandleProps()}
        sx={{
          ...handleBase,
          left: 0,
          right: 0,
          top: rowPct,
          height: 8,
          transform: 'translateY(-50%)',
          cursor: 'row-resize',
        }}
      />
    </Box>
  );
}
