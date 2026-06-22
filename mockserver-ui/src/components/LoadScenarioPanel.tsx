import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Card from '@mui/material/Card';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Link from '@mui/material/Link';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import LinearProgress from '@mui/material/LinearProgress';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import EditIcon from '@mui/icons-material/Edit';
import RefreshIcon from '@mui/icons-material/Refresh';
import SaveIcon from '@mui/icons-material/Save';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchLoadScenario,
  registerLoadScenario,
  startScenariosByName,
  stopScenariosByName,
  listLoadScenarios,
  deleteLoadScenario,
  clearLoadScenarios,
  stopLoadScenario,
  errorRate,
  formatElapsed,
  LoadScenarioError,
  type LoadScenarioDTO,
  type LoadScenarioStatus,
  type LoadScenarioState,
  type RegisteredScenario,
  type LoadStepDTO,
  type LoadStageDTO,
  type LoadStageType,
  type RampCurve,
  type LoadRequestDTO,
  type SocketAddressDTO,
} from '../lib/loadScenario';
import { buildBaseUrl } from '../lib/mcpClient';
import { useDashboardStore } from '../store';
import HumanErrorAlert from './HumanErrorAlert';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import MetricsLineChart from './MetricsLineChart';
import LoadScenarioReview from './LoadScenarioReview';

interface LoadScenarioPanelProps {
  connectionParams: ConnectionParams;
}

const RUNNING_POLL_MS = 1000;
const IDLE_POLL_MS = 5000;
const MAX_SAMPLES = 120;

const responsiveWidth = (px: number) => ({ width: { xs: '100%', sm: px } });
// Responsive grid for author-form field rows — fields fill available width and wrap
// uniformly via CSS Grid auto-fit (matching ServiceChaosPanel's CHAOS_GRID), so columns
// line up across rows instead of each row stretching its own items independently.
const FIELD_GRID = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 1, alignItems: 'start' } as const;

/**
 * Sensible starting height (in rows) for the templated body field: the default of 2 when
 * empty/small, growing to fit a loaded multi-line body up to a 20-row cap. The field stays
 * `multiline` with no `maxRows`, so MUI continues to auto-grow beyond this as the user types;
 * this only ensures an existing large body opens expanded instead of clipped.
 */
function bodyMinRows(body: string): number {
  const lines = body ? body.split('\n').length : 0;
  return Math.min(20, Math.max(2, lines));
}

/** Parse a trimmed numeric field, or undefined when blank/NaN. */
function num(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

interface KeyValueRow { id: number; key: string; value: string; }

interface StepFormState {
  id: number;
  name: string;
  method: string;
  path: string;
  host: string;
  port: string;
  scheme: 'HTTP' | 'HTTPS';
  headers: KeyValueRow[];
  body: string;
  thinkTimeMs: string;
}

/**
 * One stage card in the profile stage-builder. `mode` is a UI-only toggle distinguishing a
 * hold (single setpoint) from a ramp (start→end + curve) for VU/RATE stages; PAUSE ignores both.
 */
interface StageFormState {
  id: number;
  type: LoadStageType;
  mode: 'HOLD' | 'RAMP';
  durationMillis: string;
  curve: RampCurve;
  // VU fields
  vus: string;
  startVus: string;
  endVus: string;
  // RATE fields (iterations/sec)
  rate: string;
  startRate: string;
  endRate: string;
  maxVus: string;
}

interface FormState {
  name: string;
  templateType: 'VELOCITY' | 'MUSTACHE';
  maxRequests: string;
  startDelayMillis: string;
  stages: StageFormState[];
  labels: KeyValueRow[];
  steps: StepFormState[];
}

let idCounter = 1;
const nextId = () => idCounter++;

function emptyStep(): StepFormState {
  return {
    id: nextId(), name: '', method: 'GET', path: '/', host: '', port: '', scheme: 'HTTP', headers: [], body: '', thinkTimeMs: '',
  };
}

/** A fresh VU-hold stage card — the sensible default kind when adding a stage. */
function emptyStage(type: LoadStageType = 'VU', mode: 'HOLD' | 'RAMP' = 'HOLD'): StageFormState {
  return {
    id: nextId(), type, mode, durationMillis: '30000', curve: 'LINEAR',
    vus: '5', startVus: '1', endVus: '10',
    rate: '50', startRate: '10', endRate: '100', maxVus: '',
  };
}

const EMPTY_FORM: FormState = {
  name: '',
  templateType: 'VELOCITY',
  maxRequests: '',
  startDelayMillis: '',
  // Sensible default: ramp 1→10 VUs over 30s, then hold 10 VUs for 60s (mirrors the OpenAPI example).
  stages: [
    { ...emptyStage('VU', 'RAMP'), startVus: '1', endVus: '10', durationMillis: '30000', curve: 'LINEAR' },
    { ...emptyStage('VU', 'HOLD'), vus: '10', durationMillis: '60000' },
  ],
  labels: [],
  steps: [emptyStep()],
};

/**
 * Build the staged `profile` from the stage cards, or return a validation message. Each card emits a
 * {@link LoadStageDTO} carrying only the fields relevant to its type/mode. Validates: ≥1 stage,
 * ≥1 non-PAUSE (load) stage, every duration > 0, and ramp stages supply both endpoints.
 */
function buildProfile(stages: StageFormState[]): { stages: LoadStageDTO[] } | { error: string } {
  if (stages.length === 0) return { error: 'At least one stage is required' };
  const out: LoadStageDTO[] = [];
  let hasLoadStage = false;
  for (let i = 0; i < stages.length; i++) {
    const s = stages[i]!;
    const label = `Stage ${i + 1}`;
    const durationMillis = num(s.durationMillis);
    if (durationMillis == null || durationMillis <= 0) return { error: `${label}: duration (ms) must be greater than 0` };

    const stage: LoadStageDTO = { type: s.type, durationMillis };
    if (s.type === 'VU') {
      hasLoadStage = true;
      if (s.mode === 'HOLD') {
        const vus = num(s.vus);
        if (vus == null || vus < 1) return { error: `${label}: virtual users (VUs) must be at least 1` };
        stage.vus = vus;
      } else {
        const startVus = num(s.startVus);
        const endVus = num(s.endVus);
        if (startVus == null || startVus < 0) return { error: `${label}: start VUs must be 0 or greater` };
        if (endVus == null || endVus < 1) return { error: `${label}: end VUs must be at least 1` };
        stage.startVus = startVus;
        stage.endVus = endVus;
        stage.curve = s.curve;
      }
    } else if (s.type === 'RATE') {
      hasLoadStage = true;
      if (s.mode === 'HOLD') {
        const rate = num(s.rate);
        if (rate == null || rate <= 0) return { error: `${label}: rate (iterations/sec) must be greater than 0` };
        stage.rate = rate;
      } else {
        const startRate = num(s.startRate);
        const endRate = num(s.endRate);
        if (startRate == null || startRate < 0) return { error: `${label}: start rate must be 0 or greater` };
        if (endRate == null || endRate <= 0) return { error: `${label}: end rate must be greater than 0` };
        stage.startRate = startRate;
        stage.endRate = endRate;
        stage.curve = s.curve;
      }
      const maxVus = num(s.maxVus);
      if (maxVus != null) {
        if (maxVus < 1) return { error: `${label}: max VUs must be at least 1 (or leave blank)` };
        stage.maxVus = maxVus;
      }
    }
    // PAUSE: only durationMillis.
    out.push(stage);
  }
  if (!hasLoadStage) return { error: 'At least one VU or RATE stage is required (a profile of only pauses drives no load)' };
  return { stages: out };
}

/** Build a LoadScenarioDTO from the form, or return a validation message. */
function buildScenario(form: FormState): { scenario: LoadScenarioDTO } | { error: string } {
  if (form.name.trim() === '') return { error: 'Scenario name is required' };

  const built = buildProfile(form.stages);
  if ('error' in built) return { error: built.error };
  const profile: LoadScenarioDTO['profile'] = { stages: built.stages };

  if (form.steps.length === 0) return { error: 'At least one step is required' };
  const steps: LoadStepDTO[] = [];
  for (let i = 0; i < form.steps.length; i++) {
    const s = form.steps[i]!;
    if (s.host.trim() === '') return { error: `Step ${i + 1}: target host is required` };
    const port = num(s.port);
    if (port == null || port < 1 || port > 65535) return { error: `Step ${i + 1}: target port must be 1–65535` };
    if (s.path.trim() === '') return { error: `Step ${i + 1}: path is required` };

    const socketAddress: SocketAddressDTO = { host: s.host.trim(), port, scheme: s.scheme };
    const step: LoadStepDTO = {
      request: { method: s.method.trim() || 'GET', path: s.path.trim(), socketAddress },
    };
    const headers = headerRowsToMultiValue(s.headers);
    if (headers) step.request.headers = headers;
    if (s.body.trim() !== '') step.request.body = s.body;
    if (s.name.trim() !== '') step.name = s.name.trim();
    const thinkTimeMs = num(s.thinkTimeMs);
    if (thinkTimeMs != null) {
      if (thinkTimeMs < 0) return { error: `Step ${i + 1}: think time (ms) must be 0 or greater` };
      step.thinkTime = { timeUnit: 'MILLISECONDS', value: thinkTimeMs };
    }
    steps.push(step);
  }

  const scenario: LoadScenarioDTO = {
    name: form.name.trim(),
    templateType: form.templateType,
    profile,
    steps,
  };
  const maxRequests = num(form.maxRequests);
  if (maxRequests != null) {
    if (maxRequests < 1) return { error: 'Max requests must be at least 1 (or leave blank for unlimited)' };
    scenario.maxRequests = maxRequests;
  }
  const startDelayMillis = num(form.startDelayMillis);
  if (startDelayMillis != null) {
    if (startDelayMillis < 0) return { error: 'Start delay (ms) must be 0 or greater (or leave blank for none)' };
    scenario.startDelayMillis = startDelayMillis;
  }
  const labels = labelRowsToObject(form.labels);
  if (labels) scenario.labels = labels;

  return { scenario };
}

function labelRowsToObject(rows: KeyValueRow[]): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key !== '') out[key] = row.value;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Convert the per-step header rows into MockServer's `KeyToMultiValue` object-map form
 * (`{ "Name": ["value"] }`), collecting repeated names into a single multi-value entry.
 * Returns undefined when no non-empty header name exists so the request body stays minimal.
 */
function headerRowsToMultiValue(rows: KeyValueRow[]): Record<string, string[]> | undefined {
  const out: Record<string, string[]> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key === '') continue;
    (out[key] ??= []).push(row.value);
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Read a step's request `headers` back into editor rows. Handles both MockServer
 * `KeyToMultiValue` shapes the server may echo: the object map `{ "Name": ["v1", "v2"] }`
 * and the array form `[{ name, values: ["v1"] }]`. Each value becomes its own row so the
 * round-trip through `headerRowsToMultiValue` is faithful.
 */
function headersToRows(headers: LoadRequestDTO['headers']): KeyValueRow[] {
  if (headers == null) return [];
  const rows: KeyValueRow[] = [];
  const push = (key: string, values: unknown) => {
    if (Array.isArray(values)) {
      for (const v of values) rows.push({ id: nextId(), key, value: String(v) });
    } else if (values != null) {
      rows.push({ id: nextId(), key, value: String(values) });
    }
  };
  if (Array.isArray(headers)) {
    for (const entry of headers as Array<{ name?: unknown; values?: unknown }>) {
      if (entry && typeof entry.name === 'string') push(entry.name, entry.values);
    }
  } else {
    for (const [key, values] of Object.entries(headers as Record<string, unknown>)) {
      if (key === 'keyMatchStyle') continue;
      push(key, values);
    }
  }
  return rows;
}

/** Map one wire LoadStageDTO back into an editor stage card, inferring hold-vs-ramp mode. */
function stageToForm(stage: LoadStageDTO): StageFormState {
  const base = emptyStage(stage.type);
  const isVuRamp = stage.type === 'VU' && stage.startVus != null && stage.endVus != null;
  const isRateRamp = stage.type === 'RATE' && stage.startRate != null && stage.endRate != null;
  return {
    ...base,
    type: stage.type,
    mode: isVuRamp || isRateRamp ? 'RAMP' : 'HOLD',
    durationMillis: String(stage.durationMillis),
    curve: stage.curve ?? 'LINEAR',
    vus: stage.vus != null ? String(stage.vus) : base.vus,
    startVus: stage.startVus != null ? String(stage.startVus) : base.startVus,
    endVus: stage.endVus != null ? String(stage.endVus) : base.endVus,
    rate: stage.rate != null ? String(stage.rate) : base.rate,
    startRate: stage.startRate != null ? String(stage.startRate) : base.startRate,
    endRate: stage.endRate != null ? String(stage.endRate) : base.endRate,
    maxVus: stage.maxVus != null ? String(stage.maxVus) : '',
  };
}

/** Reverse buildScenario: load a running/most-recent scenario back into the editor. */
function scenarioToForm(scenario: LoadScenarioDTO): FormState {
  const p = scenario.profile;
  const stages = (p?.stages && p.stages.length > 0) ? p.stages.map(stageToForm) : [emptyStage('VU', 'HOLD')];
  return {
    name: scenario.name,
    templateType: scenario.templateType ?? 'VELOCITY',
    maxRequests: scenario.maxRequests != null ? String(scenario.maxRequests) : '',
    startDelayMillis: scenario.startDelayMillis != null ? String(scenario.startDelayMillis) : '',
    stages,
    labels: Object.entries(scenario.labels ?? {}).map(([key, value]) => ({ id: nextId(), key, value })),
    steps: (scenario.steps.length > 0 ? scenario.steps : [{ request: {} }]).map((step) => ({
      id: nextId(),
      name: step.name ?? '',
      method: step.request.method ?? 'GET',
      path: step.request.path ?? '/',
      host: step.request.socketAddress?.host ?? '',
      port: step.request.socketAddress?.port != null ? String(step.request.socketAddress.port) : '',
      scheme: step.request.socketAddress?.scheme ?? 'HTTP',
      headers: headersToRows(step.request.headers),
      body: step.request.body ?? '',
      thinkTimeMs: step.thinkTime?.value != null ? String(step.thinkTime.value) : '',
    })),
  };
}

// --- Combined live chart series ---

interface Sample {
  at: number;
  requestsSent: number;
  succeeded: number;
  failed: number;
  currentVus: number;
  inFlight: number;
  p50: number;
  p95: number;
  p99: number;
}

type SeriesKey = 'rps' | 'vus' | 'inFlight' | 'p50' | 'p95' | 'p99' | 'errorRate';

interface SeriesDef {
  key: SeriesKey;
  label: string;
  /** axis group: latencies share a "ms" formatter; counts/rates are plain. */
  format: (v: number) => string;
  /** derive this series' value array from the accumulated samples. */
  derive: (samples: Sample[]) => number[];
}

/** RPS for each sample as Δsent / Δt against the previous sample (first point = 0). */
function ratePerSecond(samples: Sample[]): number[] {
  return samples.map((s, i) => {
    if (i === 0) return 0;
    const prev = samples[i - 1]!;
    const dt = (s.at - prev.at) / 1000;
    if (dt <= 0) return 0;
    return Math.max(0, (s.requestsSent - prev.requestsSent) / dt);
  });
}

const SERIES_DEFS: SeriesDef[] = [
  { key: 'rps', label: 'RPS', format: (v) => `${v.toFixed(1)}/s`, derive: ratePerSecond },
  { key: 'vus', label: 'Active VUs', format: (v) => Math.round(v).toLocaleString(), derive: (s) => s.map((x) => x.currentVus) },
  { key: 'inFlight', label: 'In-flight', format: (v) => Math.round(v).toLocaleString(), derive: (s) => s.map((x) => x.inFlight) },
  { key: 'p50', label: 'p50 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p50) },
  { key: 'p95', label: 'p95 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p95) },
  { key: 'p99', label: 'p99 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p99) },
  { key: 'errorRate', label: 'Error rate %', format: (v) => `${v.toFixed(1)}%`, derive: (s) => s.map((x) => (x.requestsSent > 0 ? (x.failed / x.requestsSent) * 100 : 0)) },
];

// Default-visible subset — RPS + p95 + active VUs (per the brief).
const DEFAULT_VISIBLE: SeriesKey[] = ['rps', 'p95', 'vus'];

export default function LoadScenarioPanel({ connectionParams }: LoadScenarioPanelProps) {
  const setView = useDashboardStore((s) => s.setView);

  const [view, setPanelView] = useState<'author' | 'code'>('author');

  const [status, setStatus] = useState<LoadScenarioStatus | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<HumanError | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);

  // Registry (Wave C): all scenarios registered on the server + the user's selection.
  const [registry, setRegistry] = useState<RegisteredScenario[]>([]);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());

  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [visible, setVisible] = useState<Set<SeriesKey>>(() => new Set(DEFAULT_VISIBLE));
  // The runId whose samples are currently accumulated — reset when a new run starts.
  const sampleRunId = useRef<string | null>(null);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Poll the load scenario status. Fast (1s) while running, slower when idle.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetchLoadScenario(connectionParams, controller.signal);
        if (cancelled) return;
        setStatus(result);
        setDisabled(false);
        setLoadError(null);
        // Accumulate a sample only while a run is active and identifiable.
        if (result.state === 'running' && result.runId) {
          setSamples((prev) => {
            const fresh = sampleRunId.current !== result.runId;
            if (fresh) sampleRunId.current = result.runId ?? null;
            const base = fresh ? [] : prev;
            const sent = result.requestsSent ?? 0;
            const succeeded = result.succeeded ?? 0;
            const failed = result.failed ?? 0;
            const sample: Sample = {
              at: Date.now(),
              requestsSent: sent,
              succeeded,
              failed,
              currentVus: result.currentVus ?? 0,
              // in-flight ≈ requests dispatched but not yet completed.
              inFlight: Math.max(0, sent - succeeded - failed),
              p50: result.p50Millis ?? 0,
              p95: result.p95Millis ?? 0,
              p99: result.p99Millis ?? 0,
            };
            return [...base, sample].slice(-MAX_SAMPLES);
          });
        }
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        if (e instanceof LoadScenarioError && e.status === 403) {
          setDisabled(true);
          setLoadError(null);
        } else {
          setLoadError(e instanceof Error ? e.message : String(e));
        }
      } finally {
        if (!cancelled) {
          const running = !cancelled && status?.state === 'running';
          timer = setTimeout(() => void poll(), running ? RUNNING_POLL_MS : IDLE_POLL_MS);
        }
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
    // status?.state intentionally drives the poll cadence (running → 1s).
  }, [connectionParams, refreshTick, status?.state]);

  // Poll the registry listing. Faster while any scenario is RUNNING/PENDING so the
  // concurrent-running view and state badges stay live.
  const anyActive = registry.some((s) => s.state === 'RUNNING' || s.state === 'PENDING');
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const list = await listLoadScenarios(connectionParams, controller.signal);
        if (cancelled) return;
        setRegistry(list);
        // Drop selections for scenarios that no longer exist.
        setSelected((prev) => {
          const names = new Set(list.map((s) => s.name));
          const next = new Set([...prev].filter((n) => names.has(n)));
          return next.size === prev.size ? prev : next;
        });
      } catch {
        // Registry listing failures are non-fatal — the legacy status poll surfaces
        // the disabled/error states. Leave the last-known registry in place.
        if (cancelled || controller.signal.aborted) return;
      } finally {
        if (!cancelled) {
          timer = setTimeout(() => void poll(), anyActive ? RUNNING_POLL_MS : IDLE_POLL_MS);
        }
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick, anyActive]);

  const running = status?.state === 'running';
  const showSummary = status != null && (status.state === 'completed' || status.state === 'stopped');

  const runAction = useCallback(async (action: () => Promise<void>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      refresh();
    } catch (e) {
      if (e instanceof LoadScenarioError && e.status === 403) {
        setDisabled(true);
      } else {
        setActionError(humanizeError(e));
      }
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const handleStop = useCallback(() => {
    void runAction(async () => {
      await stopLoadScenario(connectionParams);
    });
  }, [connectionParams, runAction]);

  // Fallback for "Edit running" when the server does not echo a definition (older servers):
  // remember the last scenario this tab submitted so its full config can still be reloaded.
  const lastSubmitted = useRef<LoadScenarioDTO | null>(null);

  // Load the running scenario's config back into the editor for tweak-and-restart. Prefer the
  // server-echoed definition (works for ANY run — a client's or another tab's), and fall back to
  // this tab's last-submitted scenario only when the server did not provide one.
  const handleEditRunning = useCallback(() => {
    const definition = status?.definition ?? lastSubmitted.current;
    if (definition) {
      setForm(scenarioToForm(definition));
    }
    setActionError(null);
  }, [status?.definition]);

  // (Author flow below registers/runs explicitly via handleLoad / handleLoadAndRun.)

  // "Load" — validate + REGISTER the scenario (does not run it). Allowed even when
  // load generation is disabled. Records the submitted scenario so "Edit running"
  // can reload it. Returns the registered scenario (or null on validation error).
  const registerForm = useCallback(async (): Promise<LoadScenarioDTO | null> => {
    const built = buildScenario(form);
    if ('error' in built) {
      setActionError({ message: built.error });
      return null;
    }
    lastSubmitted.current = built.scenario;
    let ok = false;
    await runAction(async () => {
      await registerLoadScenario(connectionParams, built.scenario);
      ok = true;
    });
    return ok ? built.scenario : null;
  }, [connectionParams, form, runAction]);

  const handleLoad = useCallback(() => {
    void registerForm();
  }, [registerForm]);

  // "Load & Run" — register, then start it by name (requires loadGenerationEnabled).
  const handleLoadAndRun = useCallback(() => {
    void (async () => {
      const scenario = await registerForm();
      if (!scenario) return;
      await runAction(async () => {
        await startScenariosByName(connectionParams, [scenario.name]);
        setSamples([]);
        sampleRunId.current = null;
      });
    })();
  }, [connectionParams, registerForm, runAction]);

  // --- registry (Wave C) handlers ---
  const toggleSelected = useCallback((name: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  }, []);

  const startSelected = useCallback(() => {
    const names = [...selected];
    if (names.length === 0) return;
    void runAction(async () => {
      await startScenariosByName(connectionParams, names);
    });
  }, [connectionParams, runAction, selected]);

  const startSelectedOne = useCallback((name: string) => {
    void runAction(async () => {
      await startScenariosByName(connectionParams, [name]);
    });
  }, [connectionParams, runAction]);

  const stopOne = useCallback((name: string) => {
    void runAction(async () => {
      await stopScenariosByName(connectionParams, [name]);
    });
  }, [connectionParams, runAction]);

  const deleteOne = useCallback((name: string) => {
    void runAction(async () => {
      await deleteLoadScenario(connectionParams, name);
    });
  }, [connectionParams, runAction]);

  const clearAll = useCallback(() => {
    void runAction(async () => {
      await clearLoadScenarios(connectionParams);
    });
  }, [connectionParams, runAction]);

  // Load a registered scenario's definition back into the author form for tweaking.
  const editRegistered = useCallback((entry: RegisteredScenario) => {
    if (entry.definition) {
      setForm(scenarioToForm(entry.definition));
      setPanelView('author');
      setActionError(null);
    }
  }, []);

  // --- form field setters ---
  const setField = <K extends keyof FormState>(field: K) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const setStepField = (index: number, field: keyof StepFormState) =>
    (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === index ? { ...s, [field]: e.target.value } : s)) }));

  const addStep = () => setForm((prev) => ({ ...prev, steps: [...prev.steps, emptyStep()] }));
  const removeStep = (index: number) => setForm((prev) => ({ ...prev, steps: prev.steps.filter((_, i) => i !== index) }));

  // --- stage builder handlers ---
  const setStageField = (index: number, field: keyof StageFormState) =>
    (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, stages: prev.stages.map((s, i) => (i === index ? { ...s, [field]: e.target.value } : s)) }));
  const setStageValue = <K extends keyof StageFormState>(index: number, field: K, value: StageFormState[K]) =>
    setForm((prev) => ({ ...prev, stages: prev.stages.map((s, i) => (i === index ? { ...s, [field]: value } : s)) }));
  const addStage = () => setForm((prev) => ({ ...prev, stages: [...prev.stages, emptyStage('VU', 'HOLD')] }));
  const removeStage = (index: number) => setForm((prev) => ({ ...prev, stages: prev.stages.filter((_, i) => i !== index) }));
  const moveStage = (index: number, delta: number) =>
    setForm((prev) => {
      const target = index + delta;
      if (target < 0 || target >= prev.stages.length) return prev;
      const stages = [...prev.stages];
      const [moved] = stages.splice(index, 1);
      stages.splice(target, 0, moved!);
      return { ...prev, stages };
    });

  const updateStepHeaders = (stepIndex: number, fn: (headers: KeyValueRow[]) => KeyValueRow[]) =>
    setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === stepIndex ? { ...s, headers: fn(s.headers) } : s)) }));
  const addStepHeader = (stepIndex: number) =>
    updateStepHeaders(stepIndex, (headers) => [...headers, { id: nextId(), key: '', value: '' }]);
  const removeStepHeader = (stepIndex: number, headerIndex: number) =>
    updateStepHeaders(stepIndex, (headers) => headers.filter((_, i) => i !== headerIndex));
  const setStepHeaderField = (stepIndex: number, headerIndex: number, field: 'key' | 'value') =>
    (e: ChangeEvent<HTMLInputElement>) =>
      updateStepHeaders(stepIndex, (headers) => headers.map((h, i) => (i === headerIndex ? { ...h, [field]: e.target.value } : h)));

  const addLabel = () => setForm((prev) => ({ ...prev, labels: [...prev.labels, { id: nextId(), key: '', value: '' }] }));
  const removeLabel = (index: number) => setForm((prev) => ({ ...prev, labels: prev.labels.filter((_, i) => i !== index) }));
  const setLabelField = (index: number, field: 'key' | 'value') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, labels: prev.labels.map((l, i) => (i === index ? { ...l, [field]: e.target.value } : l)) }));

  const toggleSeries = (key: SeriesKey) =>
    setVisible((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  // Build the chart series from the accumulated samples, only for visible keys.
  const chartSeries = useMemo(
    () => SERIES_DEFS.filter((d) => visible.has(d.key)).map((d) => ({ label: d.label, data: d.derive(samples) })),
    [samples, visible],
  );
  const chartTimestamps = useMemo(() => samples.map((s) => s.at), [samples]);

  const baseUrl = useMemo(() => buildBaseUrl(connectionParams), [connectionParams]);

  // Every registered scenario that is currently RUNNING — drives the concurrent-running view.
  const runningEntries = useMemo(
    () => registry.filter((s) => s.state === 'RUNNING' && s.status),
    [registry],
  );

  // The current authored scenario built from the form — drives the Code view. When the
  // form is incomplete this carries the validation message so the Code tab can explain why.
  const built = useMemo(() => buildScenario(form), [form]);
  const codeScenario = 'scenario' in built ? built.scenario : null;
  const codeError = 'error' in built ? built.error : null;

  const statusColor = running ? 'success' : status?.state === 'completed' ? 'info' : status?.state === 'stopped' ? 'warning' : 'default';

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }} data-testid="load-scenario-panel">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Performance — Load Scenarios</Typography>
        <Chip size="small" label={status?.state ?? 'none'} color={statusColor} variant="outlined" />
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh load scenario status">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Tabs
        value={view === 'code' ? 1 : 0}
        onChange={(_, v: number) => setPanelView(v === 1 ? 'code' : 'author')}
        sx={{ mb: 1.5, minHeight: 36, '& .MuiTab-root': { minHeight: 36, py: 0.5 } }}
      >
        <Tab label="Author" />
        <Tab label="Code" />
      </Tabs>

      {disabled && (
        <Alert severity="info" sx={{ mb: 1.5 }} data-testid="load-disabled-alert">
          <AlertTitle>Load generation is disabled</AlertTitle>
          Load scenarios are off by default. Start MockServer with load generation enabled to drive
          traffic from here:
          <Box component="pre" sx={{ mt: 1, mb: 1, p: 1, bgcolor: 'action.hover', borderRadius: 1, typography: 'subtitle2', fontWeight: 400, overflow: 'auto' }}>
{`-Dmockserver.loadGenerationEnabled=true
# or environment variable:
MOCKSERVER_LOAD_GENERATION_ENABLED=true`}
          </Box>
          For deeper analysis, the{' '}
          <Link component="button" type="button" onClick={() => setView('metrics')}>Metrics tab</Link>{' '}
          surfaces the <code>mock_server_load_*</code> Prometheus/OTEL series. See the{' '}
          <Link href="https://www.mock-server.com/mock_server/load_injection.html" target="_blank" rel="noopener noreferrer">
            load injection docs
          </Link>.
        </Alert>
      )}

      {loadError && !disabled && (
        <Alert severity="error" sx={{ mb: 1.5 }} action={
          <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
        }>
          <AlertTitle>Could not load scenario status</AlertTitle>
          {loadError}
        </Alert>
      )}

      {actionError && (
        <HumanErrorAlert error={actionError} onClose={() => setActionError(null)} sx={{ mb: 1.5 }} data-testid="load-action-error" />
      )}

      {view === 'code' ? (
        codeScenario ? (
          <LoadScenarioReview scenario={codeScenario} baseUrl={baseUrl} />
        ) : (
          <Alert severity="info" sx={{ mb: 1.5 }} data-testid="load-code-incomplete">
            <AlertTitle>Complete the scenario to generate code</AlertTitle>
            {codeError ?? 'Fill in the author form to preview register & start client code.'}
          </Alert>
        )
      ) : (
      <>

      {/* Registered scenarios (Wave C) */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-registry">
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>Registered scenarios</Typography>
          <Chip size="small" label={registry.length} variant="outlined" />
          <Box sx={{ flex: 1 }} />
          <Button
            size="small" variant="contained" startIcon={<PlayArrowIcon />}
            disabled={busy || disabled || selected.size === 0} onClick={startSelected}
            data-testid="load-start-selected"
          >
            Start selected{selected.size > 0 ? ` (${selected.size})` : ''}
          </Button>
          <Button
            size="small" color="error" variant="outlined" startIcon={<DeleteSweepIcon />}
            disabled={busy || registry.length === 0} onClick={clearAll}
            data-testid="load-clear-all"
          >
            Clear all
          </Button>
        </Box>
        {registry.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No scenarios registered yet. Use <strong>Load</strong> below to register one without running it,
            or <strong>Load &amp; Run</strong> to register and start it.
          </Typography>
        ) : (
          registry.map((entry) => (
            <Box
              key={entry.name}
              data-testid={`load-registry-row-${entry.name}`}
              sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.5, borderTop: 1, borderColor: 'divider', flexWrap: 'wrap' }}
            >
              <Checkbox
                size="small"
                checked={selected.has(entry.name)}
                onChange={() => toggleSelected(entry.name)}
                slotProps={{ input: { 'aria-label': `Select ${entry.name}` } }}
              />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{entry.name}</Typography>
              <Chip
                size="small" variant="outlined"
                color={registryStateColor(entry.state)}
                label={entry.state}
                data-testid={`load-registry-state-${entry.name}`}
              />
              {entry.status && (entry.state === 'RUNNING' || entry.state === 'COMPLETED' || entry.state === 'STOPPED') && (
                <Typography variant="caption" color="text.secondary">
                  {(entry.status.requestsSent ?? 0).toLocaleString()} sent · {(errorRate(entry.status) * 100).toFixed(1)}% err
                  {entry.status.currentVus != null ? ` · ${entry.status.currentVus} VUs` : ''}
                </Typography>
              )}
              <Box sx={{ flex: 1 }} />
              {entry.definition && (
                <Tooltip title="Load into author form">
                  <span>
                    <IconButton size="small" onClick={() => editRegistered(entry)} aria-label={`Edit ${entry.name}`}>
                      <EditIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
              {(entry.state === 'RUNNING' || entry.state === 'PENDING') ? (
                <Tooltip title="Stop">
                  <span>
                    <IconButton size="small" color="error" disabled={busy} onClick={() => stopOne(entry.name)} aria-label={`Stop ${entry.name}`}>
                      <StopIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              ) : (
                <Tooltip title={disabled ? 'Load generation disabled' : 'Start'}>
                  <span>
                    <IconButton size="small" color="primary" disabled={busy || disabled} onClick={() => startSelectedOne(entry.name)} aria-label={`Start ${entry.name}`}>
                      <PlayArrowIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
              <Tooltip title="Remove from registry">
                <span>
                  <IconButton size="small" disabled={busy} onClick={() => deleteOne(entry.name)} aria-label={`Delete ${entry.name}`}>
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            </Box>
          ))
        )}
      </Paper>

      {/* Concurrent running view (Wave C): every actively-running registered scenario. */}
      {runningEntries.length > 0 && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-running-scenarios">
          <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
            Running now ({runningEntries.length})
          </Typography>
          {runningEntries.map((entry) => {
            const st = entry.status;
            return (
              <Box key={entry.name} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-running-${entry.name}`}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{entry.name}</Typography>
                  {st?.elapsedMillis != null && (
                    <Chip size="small" variant="outlined" label={`${formatElapsed(st.elapsedMillis)} elapsed`} />
                  )}
                  {st && stageReadout(st) && (
                    <Chip size="small" color="primary" variant="outlined" label={stageReadout(st)} />
                  )}
                  <Box sx={{ flex: 1 }} />
                  <Button size="small" color="error" variant="outlined" startIcon={<StopIcon />} disabled={busy} onClick={() => stopOne(entry.name)}>
                    Stop
                  </Button>
                </Box>
                <LinearProgress sx={{ mb: 1 }} />
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))', gap: 1 }}>
                  <Stat label="Active VUs" value={(st?.currentVus ?? 0).toLocaleString()} />
                  <Stat label="Requests sent" value={(st?.requestsSent ?? 0).toLocaleString()} />
                  <Stat label="Succeeded" value={(st?.succeeded ?? 0).toLocaleString()} />
                  <Stat label="Failed" value={(st?.failed ?? 0).toLocaleString()} />
                  <Stat label="Error rate" value={st ? `${(errorRate(st) * 100).toFixed(1)}%` : '—'} />
                  <Stat label="p95" value={`${(st?.p95Millis ?? 0).toFixed(0)} ms`} />
                </Box>
              </Box>
            );
          })}
        </Paper>
      )}

      {/* Live status while a run is active */}
      {running && status && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-live-status">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
            <Typography variant="caption" color="text.secondary">
              Running{status.name ? ` · ${status.name}` : ''}{status.runId ? ` · run ${status.runId}` : ''}
            </Typography>
            <Chip size="small" label={`${formatElapsed(status.elapsedMillis ?? 0)} elapsed`} variant="outlined" />
            {stageReadout(status) && (
              <Chip size="small" color="primary" variant="outlined" label={stageReadout(status)} data-testid="load-stage-readout" />
            )}
            <Box sx={{ flex: 1 }} />
            <Button size="small" variant="outlined" startIcon={<EditIcon />} disabled={busy} onClick={handleEditRunning}>
              Edit running
            </Button>
            <Button size="small" color="error" variant="contained" startIcon={<StopIcon />} disabled={busy} onClick={handleStop}>
              Stop
            </Button>
          </Box>
          <LinearProgress sx={{ mb: 1 }} />
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 1 }}>
            <Stat label="Active VUs" value={(status.currentVus ?? 0).toLocaleString()} />
            <Stat label="Requests sent" value={(status.requestsSent ?? 0).toLocaleString()} />
            <Stat label="Succeeded" value={(status.succeeded ?? 0).toLocaleString()} />
            <Stat label="Failed" value={(status.failed ?? 0).toLocaleString()} />
            <Stat label="Error rate" value={`${(errorRate(status) * 100).toFixed(1)}%`} />
            <Stat label="p50" value={`${(status.p50Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p95" value={`${(status.p95Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p99" value={`${(status.p99Millis ?? 0).toFixed(0)} ms`} />
          </Box>
          {status.labels && Object.keys(status.labels).length > 0 && (
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mt: 1 }}>
              {Object.entries(status.labels).map(([k, v]) => (
                <Chip key={k} size="small" variant="outlined" label={`${k}=${v}`} />
              ))}
            </Box>
          )}
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Re-submitting the form below (Start) replaces and restarts the active run.
          </Typography>
        </Paper>
      )}

      {/* Combined live chart with toggleable series */}
      {(running || samples.length > 0) && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-chart">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 0.5 }}>
            <Typography variant="caption" color="text.secondary">Live throughput &amp; latency</Typography>
          </Box>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mb: 0.5 }} role="group" aria-label="Chart series toggles">
            {SERIES_DEFS.map((d) => (
              <FormControlLabel
                key={d.key}
                sx={{ mr: 1 }}
                control={
                  <Checkbox
                    size="small"
                    checked={visible.has(d.key)}
                    onChange={() => toggleSeries(d.key)}
                  />
                }
                label={<Typography variant="caption">{d.label}</Typography>}
              />
            ))}
          </Box>
          {chartSeries.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
              Select at least one series to plot.
            </Typography>
          ) : (
            <MetricsLineChart series={chartSeries} timestamps={chartTimestamps} height={220} />
          )}
        </Paper>
      )}

      {/* Key metrics summary on stop/completion */}
      {showSummary && status && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-summary">
          <Typography variant="caption" color="text.secondary">
            {status.state === 'completed' ? 'Run complete' : 'Run stopped'} — key metrics
            {status.name ? ` · ${status.name}` : ''}
          </Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 1, mt: 1 }}>
            <Stat label="Requests sent" value={(status.requestsSent ?? 0).toLocaleString()} />
            <Stat label="Succeeded" value={(status.succeeded ?? 0).toLocaleString()} />
            <Stat label="Failed" value={(status.failed ?? 0).toLocaleString()} />
            <Stat label="Error rate" value={`${(errorRate(status) * 100).toFixed(1)}%`} />
            <Stat label="Peak VUs" value={peakVus(samples, status).toLocaleString()} />
            <Stat label="Throughput" value={`${throughput(status).toFixed(1)}/s`} />
            <Stat label="p50" value={`${(status.p50Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p95" value={`${(status.p95Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p99" value={`${(status.p99Millis ?? 0).toFixed(0)} ms`} />
          </Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Deeper analysis lives in the{' '}
            <Link component="button" type="button" onClick={() => setView('metrics')}>Metrics tab</Link>{' '}
            (the <code>mock_server_load_*</code> series).
          </Typography>
        </Paper>
      )}

      {/* Author form */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-author-form">
        <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
          {running ? 'Edit / restart scenario' : 'Create a load scenario'}
        </Typography>

        {/* Scenario-level fields in their own uniform grid (matching ServiceChaosPanel's
            CHAOS_GRID idiom). The profile fields follow as a separate labelled group using
            the same FIELD_GRID so column widths line up across both groups. */}
        <Box sx={FIELD_GRID}>
          <TextField
            label="Scenario name" size="small" required value={form.name}
            onChange={setField('name')} fullWidth
          />
          <TextField
            select label="Template type" size="small" value={form.templateType}
            onChange={(e) => setForm((p) => ({ ...p, templateType: e.target.value as FormState['templateType'] }))}
            helperText="Engine for rendering templated step path/body (whole scenario)"
            fullWidth
          >
            <MenuItem value="VELOCITY">Velocity</MenuItem>
            <MenuItem value="MUSTACHE">Mustache</MenuItem>
          </TextField>
          <TextField
            label="Max requests (optional)" size="small" value={form.maxRequests}
            onChange={setField('maxRequests')} fullWidth placeholder="unlimited"
          />
          <TextField
            label="Start delay (ms)" size="small" value={form.startDelayMillis}
            onChange={setField('startDelayMillis')} fullWidth placeholder="none"
            helperText="Wait this long after Start before driving load"
          />
        </Box>

        {/* Profile — staged builder (Load Profile v2): an ordered list of stages run in sequence. */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Profile (stages)</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addStage}>Add stage</Button>
        </Box>
        {form.stages.map((stage, i) => (
          <Box key={stage.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-stage-${i}`}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">Stage {i + 1}</Typography>
              <Box sx={{ flex: 1 }} />
              <IconButton size="small" onClick={() => moveStage(i, -1)} aria-label={`Move stage ${i + 1} up`} disabled={i === 0}>
                <ArrowUpwardIcon fontSize="small" />
              </IconButton>
              <IconButton size="small" onClick={() => moveStage(i, 1)} aria-label={`Move stage ${i + 1} down`} disabled={i === form.stages.length - 1}>
                <ArrowDownwardIcon fontSize="small" />
              </IconButton>
              <IconButton size="small" onClick={() => removeStage(i)} aria-label={`Remove stage ${i + 1}`} disabled={form.stages.length <= 1}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
            <Box sx={FIELD_GRID}>
              <TextField
                select label="Stage type" size="small" value={stage.type}
                onChange={(e) => setStageValue(i, 'type', e.target.value as LoadStageType)}
                fullWidth
              >
                <MenuItem value="VU">VU (virtual users)</MenuItem>
                <MenuItem value="RATE">Rate (iterations/sec)</MenuItem>
                <MenuItem value="PAUSE">Pause (no load)</MenuItem>
              </TextField>
              {stage.type !== 'PAUSE' && (
                <TextField
                  select label="Mode" size="small" value={stage.mode}
                  onChange={(e) => setStageValue(i, 'mode', e.target.value as StageFormState['mode'])}
                  fullWidth
                >
                  <MenuItem value="HOLD">Hold</MenuItem>
                  <MenuItem value="RAMP">Ramp</MenuItem>
                </TextField>
              )}
              {stage.type === 'VU' && stage.mode === 'HOLD' && (
                <TextField label="Virtual users (VUs)" size="small" value={stage.vus} onChange={setStageField(i, 'vus')} fullWidth />
              )}
              {stage.type === 'VU' && stage.mode === 'RAMP' && (
                <>
                  <TextField label="Start VUs" size="small" value={stage.startVus} onChange={setStageField(i, 'startVus')} fullWidth />
                  <TextField label="End VUs" size="small" value={stage.endVus} onChange={setStageField(i, 'endVus')} fullWidth />
                </>
              )}
              {stage.type === 'RATE' && stage.mode === 'HOLD' && (
                <TextField label="Rate (iterations/sec)" size="small" value={stage.rate} onChange={setStageField(i, 'rate')} fullWidth />
              )}
              {stage.type === 'RATE' && stage.mode === 'RAMP' && (
                <>
                  <TextField label="Start rate (iterations/sec)" size="small" value={stage.startRate} onChange={setStageField(i, 'startRate')} fullWidth />
                  <TextField label="End rate (iterations/sec)" size="small" value={stage.endRate} onChange={setStageField(i, 'endRate')} fullWidth />
                </>
              )}
              {stage.type === 'RATE' && (
                <TextField label="Max VUs (optional)" size="small" value={stage.maxVus} onChange={setStageField(i, 'maxVus')} fullWidth placeholder="global cap" />
              )}
              {stage.type !== 'PAUSE' && stage.mode === 'RAMP' && (
                <TextField
                  select label="Curve" size="small" value={stage.curve}
                  onChange={(e) => setStageValue(i, 'curve', e.target.value as RampCurve)}
                  fullWidth
                >
                  <MenuItem value="LINEAR">Linear</MenuItem>
                  <MenuItem value="EXPONENTIAL">Exponential</MenuItem>
                  <MenuItem value="QUADRATIC">Quadratic</MenuItem>
                </TextField>
              )}
              <TextField label="Duration (ms)" size="small" value={stage.durationMillis} onChange={setStageField(i, 'durationMillis')} fullWidth />
            </Box>
          </Box>
        ))}

        {/* Labels */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Labels (optional)</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addLabel}>Add label</Button>
        </Box>
        {form.labels.map((label, i) => (
          <Box key={label.id} sx={{ display: 'flex', gap: 1, mt: 0.5, mb: 1.25, alignItems: 'center', flexWrap: 'wrap' }}>
            <TextField label="Key" size="small" value={label.key} onChange={setLabelField(i, 'key')} sx={responsiveWidth(160)} />
            <TextField label="Value" size="small" value={label.value} onChange={setLabelField(i, 'value')} sx={responsiveWidth(160)} />
            <IconButton size="small" onClick={() => removeLabel(i)} aria-label={`Remove label ${i + 1}`}><DeleteIcon fontSize="small" /></IconButton>
          </Box>
        ))}

        {/* Steps */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Steps</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addStep}>Add step</Button>
        </Box>
        {form.steps.map((step, i) => (
          <Box key={step.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-step-${i}`}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">Step {i + 1}</Typography>
              <Box sx={{ flex: 1 }} />
              <IconButton size="small" onClick={() => removeStep(i)} aria-label={`Remove step ${i + 1}`} disabled={form.steps.length <= 1}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
            {/* Step fields on TWO rows so Path gets ample width. Row 1: name, method,
                Path (widest column). Row 2: the remaining four fields in the uniform
                auto-fit FIELD_GRID. */}
            <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(140px, 1fr) minmax(110px, 0.6fr) minmax(260px, 2.5fr)', gap: 1, alignItems: 'start', mb: 1 }}>
              <TextField label="Step name (optional)" size="small" value={step.name} onChange={setStepField(i, 'name')} fullWidth />
              <TextField select label="Method" size="small" value={step.method}
                onChange={(e) => setForm((p) => ({ ...p, steps: p.steps.map((s, j) => (j === i ? { ...s, method: e.target.value } : s)) }))}
                fullWidth>
                {['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map((m) => <MenuItem key={m} value={m}>{m}</MenuItem>)}
              </TextField>
              <TextField label="Path" size="small" value={step.path} onChange={setStepField(i, 'path')} fullWidth />
            </Box>
            <Box sx={FIELD_GRID}>
              <TextField label="Target host" size="small" value={step.host} onChange={setStepField(i, 'host')} fullWidth />
              <TextField label="Target port" size="small" value={step.port} onChange={setStepField(i, 'port')} fullWidth />
              <TextField select label="Scheme" size="small" value={step.scheme}
                onChange={(e) => setForm((p) => ({ ...p, steps: p.steps.map((s, j) => (j === i ? { ...s, scheme: e.target.value as 'HTTP' | 'HTTPS' } : s)) }))}
                fullWidth>
                <MenuItem value="HTTP">HTTP</MenuItem>
                <MenuItem value="HTTPS">HTTPS</MenuItem>
              </TextField>
              <TextField label="Delay after step (ms)" size="small" value={step.thinkTimeMs} onChange={setStepField(i, 'thinkTimeMs')} helperText="Wait this long after this step's request before the next step (a.k.a. think-time)" fullWidth placeholder="none" />
            </Box>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Request headers (optional)</Typography>
              <Button size="small" startIcon={<AddIcon />} onClick={() => addStepHeader(i)}>Add header</Button>
            </Box>
            {step.headers.map((header, h) => (
              <Box key={header.id} sx={{ display: 'flex', gap: 1, mt: 0.5, mb: 1.25, alignItems: 'center', flexWrap: 'wrap' }}>
                <TextField label="Header name" size="small" value={header.key} onChange={setStepHeaderField(i, h, 'key')} sx={responsiveWidth(180)} />
                <TextField label="Header value" size="small" value={header.value} onChange={setStepHeaderField(i, h, 'value')} sx={responsiveWidth(200)} />
                <IconButton size="small" onClick={() => removeStepHeader(i, h)} aria-label={`Remove header ${h + 1} of step ${i + 1}`}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Box>
            ))}
            <TextField
              label="Body (optional, templated)" size="small" value={step.body}
              onChange={setStepField(i, 'body')}
              fullWidth multiline minRows={bodyMinRows(step.body)} sx={{ mt: 1 }}
              slotProps={{ htmlInput: { style: { resize: 'vertical', overflow: 'auto' } } }}
            />
          </Box>
        ))}

        <Box sx={{ display: 'flex', gap: 1, mt: 1, flexWrap: 'wrap', alignItems: 'center' }}>
          <Button
            variant="outlined" color="primary" startIcon={<SaveIcon />}
            disabled={busy} onClick={handleLoad}
            data-testid="load-register"
          >
            Load
          </Button>
          <Button
            variant="contained" color="primary" startIcon={<PlayArrowIcon />}
            disabled={busy || disabled} onClick={handleLoadAndRun}
            data-testid="load-register-run"
          >
            Load &amp; Run
          </Button>
          <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>
            <strong>Load</strong> registers without running (allowed even when load generation is off).{' '}
            <strong>Load &amp; Run</strong> registers then starts it.
          </Typography>
        </Box>
      </Paper>

      </>
      )}
    </Box>
  );
}

/** MUI Chip colour for each registry lifecycle state. */
function registryStateColor(
  state: LoadScenarioState,
): 'default' | 'success' | 'info' | 'warning' | 'primary' {
  switch (state) {
    case 'RUNNING': return 'success';
    case 'PENDING': return 'primary';
    case 'COMPLETED': return 'info';
    case 'STOPPED': return 'warning';
    case 'LOADED':
    default: return 'default';
  }
}

/** A small label/value stat cell used in the live + summary metric grids. */
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card elevation={0} sx={{ p: 1, bgcolor: 'action.hover' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2 }}>{value}</Typography>
    </Card>
  );
}

/**
 * Compact readout of which staged-profile stage is currently active, e.g. "Stage 2/3 · RATE · target 50/s".
 * Uses the server's `stageIndex`/`stageType`/`currentTarget`; the total stage count comes from the echoed
 * definition when available. Returns '' when the server doesn't report a running stage (older servers).
 */
function stageReadout(status: LoadScenarioStatus): string {
  if (status.stageIndex == null && status.stageType == null) return '';
  const total = status.definition?.profile?.stages?.length;
  const position = status.stageIndex != null
    ? `Stage ${status.stageIndex + 1}${total ? `/${total}` : ''}`
    : 'Stage';
  const parts = [position];
  if (status.stageType) parts.push(status.stageType);
  if (status.currentTarget != null && status.stageType && status.stageType !== 'PAUSE') {
    const target = status.stageType === 'RATE'
      ? `target ${Number(status.currentTarget.toFixed(1))}/s`
      : `target ${Math.round(status.currentTarget)} VUs`;
    parts.push(target);
  }
  return parts.join(' · ');
}

/** Peak active VUs observed across samples, falling back to the final status. */
function peakVus(samples: Sample[], status: LoadScenarioStatus): number {
  const fromSamples = samples.reduce((m, s) => Math.max(m, s.currentVus), 0);
  return Math.max(fromSamples, status.currentVus ?? 0);
}

/** Overall throughput (req/s) = requestsSent / elapsed seconds. */
function throughput(status: LoadScenarioStatus): number {
  const sent = status.requestsSent ?? 0;
  const seconds = (status.elapsedMillis ?? 0) / 1000;
  if (seconds <= 0) return 0;
  return sent / seconds;
}
