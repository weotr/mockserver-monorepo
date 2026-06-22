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

export type ViewMode = 'dashboard' | 'traffic' | 'sessions' | 'composer' | 'library' | 'chaos' | 'performance' | 'metrics' | 'drift' | 'verification' | 'async' | 'grpc' | 'breakpoints' | 'contract' | 'cluster' | 'optimise' | 'get-started';

/** Map legacy/removed ViewMode values to their replacement. */
const VIEW_MIGRATION: Record<string, ViewMode> = {
  'mcp-tools': 'composer',
};

/**
 * Per-panel cache mapping each `key` to the reference currently held for it and
 * that reference's serialized form. Lets {@link reconcileByKey} compare a
 * freshly-parsed entry against the previous one with a single `JSON.stringify`
 * of the *new* entry, instead of re-serializing both sides on every push.
 *
 * One cache instance is passed per entity array (the four panels never collide).
 * The stored `str` is trusted only when the stored `ref` is identical to the
 * previous entry being compared, so a stale cache (e.g. after a direct
 * `setState` that bypasses this function) can never cause a wrong comparison.
 */
type ReconcileCache = Map<string, { ref: unknown; str: string }>;

const logMessagesCache: ReconcileCache = new Map();
const activeExpectationsCache: ReconcileCache = new Map();
const recordedRequestsCache: ReconcileCache = new Map();
const proxiedRequestsCache: ReconcileCache = new Map();

/**
 * Reconcile a freshly-parsed array against the previous one, preserving object
 * identity for entries whose content is unchanged.
 *
 * Every WebSocket push delivers brand-new parsed objects, so without this each
 * row would be a new reference on every tick — and `React.memo` on the row
 * components could never skip a re-render. Entries are matched by their stable
 * `key`; equality is a structural (JSON) compare. Unchanged entries keep their
 * previous reference, which keeps memoized rows and their `useMemo([item.value])`
 * hooks valid across pushes. The reused reference is the whole entry, so nested
 * children (e.g. a log group's entries) are preserved for free.
 *
 * Equality semantics are identical to a deep JSON compare, but each entry is
 * serialized at most once per lifetime: the previous reference's string is read
 * from {@link cache}, and only the new entry is stringified per push (the
 * previous code stringified both sides for every matched row, ~100 rows × 4
 * panels × ~1/sec). The cache stores both the kept reference and its string and
 * the cached string is only trusted when its `ref` is *identical* to the
 * previous entry — so a stale cache (e.g. after a direct `setState`) is detected
 * and the previous entry is re-serialized rather than mis-compared. When an
 * entry is unchanged we return — and keep cached — the previous reference and
 * its string; when it changes we cache the new string. Keys absent from `next`
 * are pruned so the cache cannot grow unbounded.
 */
function reconcileByKey<T extends { key: string }>(prev: T[], next: T[], cache: ReconcileCache): T[] {
  if (next.length === 0) {
    cache.clear();
    return next;
  }
  if (prev.length === 0) {
    cache.clear();
    for (const n of next) cache.set(n.key, { ref: n, str: JSON.stringify(n) });
    return next;
  }
  const prevByKey = new Map(prev.map((p) => [p.key, p] as const));
  const nextCache: ReconcileCache = new Map();
  const result = next.map((n) => {
    const p = prevByKey.get(n.key);
    if (!p) {
      nextCache.set(n.key, { ref: n, str: JSON.stringify(n) });
      return n;
    }
    const nStr = JSON.stringify(n);
    if (p === n) {
      // Same reference already held; no identity to preserve, but keep it cached.
      nextCache.set(n.key, { ref: n, str: nStr });
      return n;
    }
    // Trust the cached string only when it was recorded for *this* reference;
    // otherwise (cold/stale cache after a direct setState) re-serialize `p`.
    const cached = cache.get(n.key);
    const pStr = cached && cached.ref === p ? cached.str : JSON.stringify(p);
    if (pStr === nStr) {
      // Semantically unchanged — preserve the previous reference and its string.
      nextCache.set(n.key, { ref: p, str: pStr });
      return p;
    }
    nextCache.set(n.key, { ref: n, str: nStr });
    return n;
  });
  cache.clear();
  for (const [k, v] of nextCache) cache.set(k, v);
  return result;
}

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

  /** When false, FORWARDED_REQUEST log entries are hidden from the Log panel. */
  logShowForwarded: boolean;

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

  /**
   * An expectation handed off from the Active Expectations panel's "Edit"
   * action for the Composer to load into its form. Null when there is no
   * pending edit. The raw expectation value (the matcher + action JSON) is
   * stored as-is; the Composer consumes and then clears it.
   */
  pendingEditExpectation: Record<string, unknown> | null;

  actionTypeFilter: string[];
  llmProviderFilter: string[];

  setActionTypeFilter: (types: string[]) => void;
  setLlmProviderFilter: (providers: string[]) => void;

  /** Load an expectation into the Composer for editing and switch to that view. */
  editExpectation: (expectation: Record<string, unknown>) => void;
  /** Clear the pending edit handoff (called by the Composer once consumed). */
  clearPendingEditExpectation: () => void;

  /**
   * Pre-filled method/path to seed a new breakpoint matcher form, set when the
   * user clicks "Set breakpoint" on a log row. Consumed (and cleared) by the
   * Breakpoints panel once it has applied it to the registration form.
   */
  pendingBreakpointPrefill: { method?: string; path?: string } | null;
  /** Seed the breakpoint matcher form from a request and switch to that view. */
  setBreakpointPrefill: (prefill: { method?: string; path?: string }) => void;
  /** Clear the pending breakpoint prefill (called by the panel once consumed). */
  clearPendingBreakpointPrefill: () => void;

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
  setLogShowForwarded: (show: boolean) => void;
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

  logShowForwarded: true,

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

  pendingEditExpectation: null,
  pendingBreakpointPrefill: null,

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
    // The view is never changed here: the user stays on whatever view they are
    // on (the initial view is 'get-started') until they navigate themselves.
    // We deliberately do NOT auto-advance to the dashboard when the first data
    // arrives — landing on Get Started should be sticky.
    set((s) => ({
      logMessages: reconcileByKey(s.logMessages, message.logMessages ?? [], logMessagesCache),
      activeExpectations: reconcileByKey(s.activeExpectations, message.activeExpectations ?? [], activeExpectationsCache),
      recordedRequests: reconcileByKey(s.recordedRequests, message.recordedRequests ?? [], recordedRequestsCache),
      proxiedRequests: reconcileByKey(s.proxiedRequests, message.proxiedRequests ?? [], proxiedRequestsCache),
      error: message.error ?? null,
    })),

  clearUI: () => {
    logMessagesCache.clear();
    activeExpectationsCache.clear();
    recordedRequestsCache.clear();
    proxiedRequestsCache.clear();
    set({
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
      selectedTrafficKey: null,
      pendingEditExpectation: null,
      pendingBreakpointPrefill: null,
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
    });
  },

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
  setLogShowForwarded: (show) => set({ logShowForwarded: show }),
  setExpectationSearch: (term) => set({ expectationSearch: term }),
  setReceivedSearch: (term) => set({ receivedSearch: term }),
  setProxiedSearch: (term) => set({ proxiedSearch: term }),
  setTrafficSearch: (term) => set({ trafficSearch: term }),
  setSelectedTrafficKey: (key) => set({ selectedTrafficKey: key }),
  editExpectation: (expectation) => set({ pendingEditExpectation: expectation, view: 'composer' as ViewMode, selectedTrafficKey: null }),
  clearPendingEditExpectation: () => set({ pendingEditExpectation: null }),
  setBreakpointPrefill: (prefill) => set({ pendingBreakpointPrefill: prefill, view: 'breakpoints' as ViewMode, selectedTrafficKey: null }),
  clearPendingBreakpointPrefill: () => set({ pendingBreakpointPrefill: null }),
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
