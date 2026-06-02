import { lazy, Suspense, useCallback, useEffect, useMemo, useRef } from 'react';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import { useDashboardStore } from './store';
import { buildTheme } from './theme';
import { useConnectionParams } from './hooks/useConnectionParams';
import { useWebSocket } from './hooks/useWebSocket';
import { useDebugMismatch } from './hooks/useDebugMismatch';
import { DebugMismatchContext } from './hooks/DebugMismatchContext';
import { useGenerateStub } from './hooks/useGenerateStub';
import { GenerateStubContext } from './hooks/GenerateStubContext';
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts';
import AppBar from './components/AppBar';
import FilterPanel from './components/FilterPanel';
import DashboardGrid from './components/DashboardGrid';
import TrafficInspector from './components/TrafficInspector';
import SessionInspector from './components/SessionInspector';
import ComposerView from './components/ComposerView';
import LibraryView from './components/LibraryView';
import ServiceChaosPanel from './components/ServiceChaosPanel';
import DriftPanel from './components/DriftPanel';
import VerificationView from './components/VerificationView';
import DebugMismatchDialog from './components/DebugMismatchDialog';
import GenerateStubDialog from './components/GenerateStubDialog';
import type { RequestFilter } from './types';

// Lazy-loaded so the @mui/x-charts bundle only loads when the Metrics tab is
// opened, keeping it off the initial dashboard load.
const MetricsView = lazy(() => import('./components/MetricsView'));

export default function App() {
  const themeMode = useDashboardStore((s) => s.themeMode);
  const view = useDashboardStore((s) => s.view);
  const error = useDashboardStore((s) => s.error);
  const theme = useMemo(() => buildTheme(themeMode), [themeMode]);

  const generateStubOpen = useDashboardStore((s) => s.generateStubOpen);
  const generateStubSuggestions = useDashboardStore((s) => s.generateStubSuggestions);
  const generateStubConfidence = useDashboardStore((s) => s.generateStubConfidence);
  const closeGenerateStub = useDashboardStore((s) => s.closeGenerateStub);

  const params = useConnectionParams();
  const { connect, sendFilter, clearServer } = useWebSocket(params);
  const { debugMismatch } = useDebugMismatch(params);
  const { generateStub } = useGenerateStub(params);
  const initialConnectDone = useRef(false);

  useEffect(() => {
    if (!initialConnectDone.current) {
      initialConnectDone.current = true;
      connect({});
    }
  }, [connect]);

  const handleFilterChange = useCallback(
    (filter: RequestFilter) => {
      sendFilter(filter);
    },
    [sendFilter],
  );

  const logSearchInputRef = useRef<HTMLInputElement>(null);

  const shortcutHandlers = useMemo(
    () => ({
      onSearch: () => {
        logSearchInputRef.current?.focus();
      },
      onClear: () => {
        void clearServer('all');
      },
      onToggleFilter: () => {
        useDashboardStore.getState().toggleFilterExpanded();
      },
    }),
    [clearServer],
  );

  useKeyboardShortcuts(shortcutHandlers);

  const handleClearServer = useCallback(async () => {
    await clearServer('all');
  }, [clearServer]);

  const handleClearLogs = useCallback(async () => {
    await clearServer('log');
  }, [clearServer]);

  const handleClearExpectations = useCallback(async () => {
    await clearServer('expectations');
  }, [clearServer]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <DebugMismatchContext.Provider value={debugMismatch}>
      <GenerateStubContext.Provider value={generateStub}>
        <Box sx={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
          <AppBar
            onClearServer={handleClearServer}
            onClearLogs={handleClearLogs}
            onClearExpectations={handleClearExpectations}
          />
          {(view === 'dashboard' || view === 'traffic' || view === 'sessions') && (
            <FilterPanel onFilterChange={handleFilterChange} />
          )}
          {error && (
            <Alert severity="error" sx={{ mx: 1, mt: 1, flexShrink: 0 }}>
              {error}
            </Alert>
          )}
          {view === 'dashboard' && <DashboardGrid />}
          {view === 'traffic' && <TrafficInspector />}
          {view === 'sessions' && <SessionInspector connectionParams={params} />}
          {view === 'composer' && <ComposerView connectionParams={params} />}
          {view === 'library' && <LibraryView connectionParams={params} />}
          {view === 'metrics' && (
            <Suspense fallback={<Box sx={{ p: 2 }}>Loading metrics…</Box>}>
              <MetricsView connectionParams={params} />
            </Suspense>
          )}
          {view === 'chaos' && <ServiceChaosPanel connectionParams={params} />}
          {view === 'drift' && <DriftPanel connectionParams={params} />}
          {view === 'verification' && <VerificationView connectionParams={params} />}
        </Box>
        <DebugMismatchDialog />
        <GenerateStubDialog
          open={generateStubOpen}
          onClose={closeGenerateStub}
          suggestions={generateStubSuggestions}
          confidence={generateStubConfidence}
          connectionParams={params}
        />
      </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>
    </ThemeProvider>
  );
}
