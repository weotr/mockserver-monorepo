import { create } from 'zustand';
import type {
  ConnectionStatus,
  DebugMismatchResult,
  JsonListItem,
  LogMessage,
  RequestFilter,
  ThemeMode,
  WebSocketMessage,
} from '../types';
import { ACTION_TYPES, LLM_PROVIDERS } from '../lib/clientFilters';

export type ViewMode = 'dashboard' | 'traffic' | 'sessions' | 'composer' | 'library' | 'chaos' | 'metrics' | 'drift' | 'verification' | 'async' | 'breakpoints' | 'get-started';

/** Map legacy/removed ViewMode values to their replacement. */
const VIEW_MIGRATION: Record<string, ViewMode> = {
  'mcp-tools': 'composer',
};

interface DashboardState {
  logMessages: LogMessage[];
  activeExpectations: JsonListItem[];
  recordedRequests: JsonListItem[];
  proxiedRequests: JsonListItem[];

  view: ViewMode;
  requestFilter: RequestFilter;
  filterEnabled: boolean;
  filterExpanded: boolean;

  connectionStatus: ConnectionStatus;
  themeMode: ThemeMode;
  autoScroll: boolean;

  logSearch: string;
  expectationSearch: string;
  receivedSearch: string;
  proxiedSearch: string;
  trafficSearch: string;

  error: string | null;

  /** Transient toast/snackbar for action feedback (success/info/warning/error). */
  notification: { message: string; severity: 'success' | 'info' | 'warning' | 'error' } | null;

  debugMismatchOpen: boolean;
  debugMismatchLoading: boolean;
  debugMismatchResult: DebugMismatchResult | null;
  debugMismatchError: string | null;

  generateStubOpen: boolean;
  generateStubLoading: boolean;
  generateStubSuggestions: Record<string, unknown>[];
  generateStubConfidence: number;
  generateStubError: string | null;

  selectedTrafficKey: string | null;

  actionTypeFilter: string[];
  llmProviderFilter: string[];

  setActionTypeFilter: (types: string[]) => void;
  setLlmProviderFilter: (providers: string[]) => void;

  applyMessage: (message: WebSocketMessage) => void;
  clearUI: () => void;
  setView: (view: ViewMode) => void;
  setRequestFilter: (filter: RequestFilter) => void;
  setFilterEnabled: (enabled: boolean) => void;
  setFilterExpanded: (expanded: boolean) => void;
  toggleFilterExpanded: () => void;
  setConnectionStatus: (status: ConnectionStatus) => void;
  setThemeMode: (mode: ThemeMode) => void;
  toggleThemeMode: () => void;
  setAutoScroll: (enabled: boolean) => void;
  toggleAutoScroll: () => void;
  setLogSearch: (term: string) => void;
  setExpectationSearch: (term: string) => void;
  setReceivedSearch: (term: string) => void;
  setProxiedSearch: (term: string) => void;
  setTrafficSearch: (term: string) => void;
  setSelectedTrafficKey: (key: string | null) => void;
  setError: (error: string | null) => void;
  setNotification: (notification: { message: string; severity: 'success' | 'info' | 'warning' | 'error' } | null) => void;
  openDebugMismatch: (result: DebugMismatchResult) => void;
  closeDebugMismatch: () => void;
  setDebugMismatchLoading: (loading: boolean) => void;
  setDebugMismatchError: (error: string | null) => void;

  openGenerateStub: (suggestions: Record<string, unknown>[], confidence: number) => void;
  closeGenerateStub: () => void;
  setGenerateStubLoading: (loading: boolean) => void;
  setGenerateStubError: (error: string | null) => void;
}

function getInitialTheme(): ThemeMode {
  try {
    const stored = globalThis.localStorage?.getItem('mockserver-theme');
    if (stored === 'dark' || stored === 'light') return stored;
  } catch {
    // localStorage may not be available in test/SSR environments
  }
  return 'dark';
}

export const useDashboardStore = create<DashboardState>()((set) => ({
  logMessages: [],
  activeExpectations: [],
  recordedRequests: [],
  proxiedRequests: [],

  view: 'get-started' as ViewMode,
  requestFilter: {},
  filterEnabled: false,
  filterExpanded: false,

  connectionStatus: 'disconnected',
  themeMode: getInitialTheme(),
  autoScroll: true,

  logSearch: '',
  expectationSearch: '',
  receivedSearch: '',
  proxiedSearch: '',
  trafficSearch: '',

  error: null,
  notification: null,

  debugMismatchOpen: false,
  debugMismatchLoading: false,
  debugMismatchResult: null,
  debugMismatchError: null,

  generateStubOpen: false,
  generateStubLoading: false,
  generateStubSuggestions: [],
  generateStubConfidence: 0,
  generateStubError: null,

  selectedTrafficKey: null,

  actionTypeFilter: [],
  llmProviderFilter: [],

  // Whitelist incoming filter values so a stale URL or serialised state cannot
  // poison the store with a value that silently matches nothing.
  setActionTypeFilter: (types) => set({
    actionTypeFilter: types.filter((t) => (ACTION_TYPES as readonly string[]).includes(t)),
  }),
  setLlmProviderFilter: (providers) => set({
    llmProviderFilter: providers.filter((p) => (LLM_PROVIDERS as readonly string[]).includes(p)),
  }),

  applyMessage: (message) =>
    set((s) => {
      const expectations = message.activeExpectations ?? [];
      const recorded = message.recordedRequests ?? [];
      const proxied = message.proxiedRequests ?? [];
      // Auto-navigate from the onboarding view to the dashboard only on the
      // empty→has-data transition (i.e. the PREVIOUS state had zero data and
      // the incoming message brings some). This preserves the first-run
      // auto-advance while letting users revisit the onboarding view at will.
      const previouslyEmpty =
        s.activeExpectations.length === 0 &&
        s.recordedRequests.length === 0 &&
        s.proxiedRequests.length === 0;
      const hasData =
        expectations.length > 0 || recorded.length > 0 || proxied.length > 0;
      const autoSwitch =
        s.view === 'get-started' && previouslyEmpty && hasData;
      return {
        logMessages: message.logMessages ?? [],
        activeExpectations: expectations,
        recordedRequests: recorded,
        proxiedRequests: proxied,
        error: message.error ?? null,
        ...(autoSwitch ? { view: 'dashboard' as ViewMode } : {}),
      };
    }),

  clearUI: () =>
    set({
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
      selectedTrafficKey: null,
      error: null,
      notification: null,
      view: 'get-started' as ViewMode,

      debugMismatchOpen: false,
      debugMismatchLoading: false,
      debugMismatchResult: null,
      debugMismatchError: null,

      generateStubOpen: false,
      generateStubLoading: false,
      generateStubSuggestions: [],
      generateStubConfidence: 0,
      generateStubError: null,
    }),

  setView: (view) => {
    const resolved = VIEW_MIGRATION[view as string] ?? view;
    set({ view: resolved, selectedTrafficKey: null });
  },
  setRequestFilter: (filter) => set({ requestFilter: filter }),
  setFilterEnabled: (enabled) => set({ filterEnabled: enabled }),
  setFilterExpanded: (expanded) => set({ filterExpanded: expanded }),
  toggleFilterExpanded: () => set((s) => ({ filterExpanded: !s.filterExpanded })),
  setConnectionStatus: (status) => set({ connectionStatus: status }),
  setThemeMode: (mode) => {
    try { globalThis.localStorage?.setItem('mockserver-theme', mode); } catch { /* noop */ }
    set({ themeMode: mode });
  },
  toggleThemeMode: () =>
    set((s) => {
      const next = s.themeMode === 'dark' ? 'light' : 'dark';
      try { globalThis.localStorage?.setItem('mockserver-theme', next); } catch { /* noop */ }
      return { themeMode: next };
    }),
  setAutoScroll: (enabled) => set({ autoScroll: enabled }),
  toggleAutoScroll: () => set((s) => ({ autoScroll: !s.autoScroll })),
  setLogSearch: (term) => set({ logSearch: term }),
  setExpectationSearch: (term) => set({ expectationSearch: term }),
  setReceivedSearch: (term) => set({ receivedSearch: term }),
  setProxiedSearch: (term) => set({ proxiedSearch: term }),
  setTrafficSearch: (term) => set({ trafficSearch: term }),
  setSelectedTrafficKey: (key) => set({ selectedTrafficKey: key }),
  setError: (error) => set({ error }),
  setNotification: (notification) => set({ notification }),
  openDebugMismatch: (result) =>
    set({ debugMismatchOpen: true, debugMismatchResult: result, debugMismatchLoading: false, debugMismatchError: null }),
  closeDebugMismatch: () =>
    set({ debugMismatchOpen: false, debugMismatchResult: null, debugMismatchLoading: false, debugMismatchError: null }),
  setDebugMismatchLoading: (loading) => set({ debugMismatchLoading: loading }),
  setDebugMismatchError: (error) =>
    set({ debugMismatchError: error, debugMismatchLoading: false }),

  openGenerateStub: (suggestions, confidence) =>
    set({ generateStubOpen: true, generateStubSuggestions: suggestions, generateStubConfidence: confidence, generateStubLoading: false, generateStubError: null }),
  closeGenerateStub: () =>
    set({ generateStubOpen: false, generateStubSuggestions: [], generateStubConfidence: 0, generateStubLoading: false, generateStubError: null }),
  setGenerateStubLoading: (loading) => set({ generateStubLoading: loading }),
  setGenerateStubError: (error) =>
    set({ generateStubError: error, generateStubLoading: false }),
}));
