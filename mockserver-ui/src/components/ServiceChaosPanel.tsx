import { useCallback, useEffect, useMemo, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Collapse from '@mui/material/Collapse';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Select from '@mui/material/Select';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import FormControlLabel from '@mui/material/FormControlLabel';
import Switch from '@mui/material/Switch';
import DeleteIcon from '@mui/icons-material/Delete';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import EditIcon from '@mui/icons-material/Edit';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import RefreshIcon from '@mui/icons-material/Refresh';
import RestoreIcon from '@mui/icons-material/Restore';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchServiceChaos,
  registerServiceChaos,
  removeServiceChaos,
  clearServiceChaos,
  patchServiceChaos,
  summarizeChaosProfile,
  formatTtl,
  type HttpChaosProfileDTO,
  type ServiceChaosResponse,
} from '../lib/serviceChaos';
import {
  fetchGrpcHealth,
  setGrpcHealth,
  resetGrpcHealth,
  type ServingStatus,
} from '../lib/grpcHealth';
import {
  fetchTcpChaos,
  registerTcpChaos,
  removeTcpChaos,
  clearTcpChaos,
  summarizeTcpChaosProfile,
  type TcpChaosProfileDTO,
  type TcpChaosResponse,
} from '../lib/tcpChaos';
import {
  fetchGrpcChaos,
  registerGrpcChaos,
  removeGrpcChaos,
  clearGrpcChaos,
  summarizeGrpcChaosProfile,
  type GrpcChaosProfileDTO,
  type GrpcChaosResponse,
} from '../lib/grpcChaos';

interface ServiceChaosPanelProps {
  connectionParams: ConnectionParams;
}

const POLL_INTERVAL_MS = 4000;

interface FormState {
  host: string;
  errorStatus: string;
  errorProbability: string;
  dropProbability: string;
  latencyMs: string;
  ttlMs: string;
}

const EMPTY_FORM: FormState = {
  host: '',
  errorStatus: '',
  errorProbability: '',
  dropProbability: '',
  latencyMs: '',
  ttlMs: '',
};

/** Parse a trimmed numeric field, or undefined when blank. NaN is treated as undefined. */
function num(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

function buildChaosProfile(form: FormState): HttpChaosProfileDTO {
  const profile: HttpChaosProfileDTO = {};
  const errorStatus = num(form.errorStatus);
  if (errorStatus != null) {
    profile.errorStatus = errorStatus;
    // errorProbability only has effect alongside an errorStatus, so it is sent
    // only when a status is present (a stray probability would be a silent no-op).
    const errorProbability = num(form.errorProbability);
    if (errorProbability != null) profile.errorProbability = errorProbability;
  }
  const dropProbability = num(form.dropProbability);
  if (dropProbability != null) profile.dropConnectionProbability = dropProbability;
  const latencyMs = num(form.latencyMs);
  if (latencyMs != null) profile.latency = { timeUnit: 'MILLISECONDS', value: latencyMs };
  return profile;
}

/** Returns a validation message for the form, or null when it is valid to submit. */
function validateForm(form: FormState): string | null {
  if (form.host.trim() === '') return 'Host is required';
  const errorStatus = num(form.errorStatus);
  if (form.errorStatus.trim() !== '' && (errorStatus == null || !Number.isInteger(errorStatus) || errorStatus < 100 || errorStatus > 599)) {
    return 'Error status must be a whole number between 100 and 599';
  }
  if (num(form.errorProbability) != null && errorStatus == null) {
    return 'Error probability needs an error status (e.g. 503)';
  }
  for (const [field, label] of [['errorProbability', 'Error probability'], ['dropProbability', 'Drop probability']] as const) {
    const value = num(form[field]);
    if (value != null && (value < 0 || value > 1)) return `${label} must be between 0 and 1`;
  }
  const latencyMs = num(form.latencyMs);
  if (latencyMs != null && latencyMs < 0) return 'Latency must be 0 or greater';
  const ttl = num(form.ttlMs);
  if (ttl != null && (!Number.isInteger(ttl) || ttl < 1)) return 'TTL must be a whole number of milliseconds ≥ 1';
  if (summarizeChaosProfile(buildChaosProfile(form)).length === 0) {
    return 'Set at least one fault (error status, drop, or latency)';
  }
  return null;
}

interface EditFormState {
  errorStatus: string;
  errorProbability: string;
  dropProbability: string;
  latencyMs: string;
}

// --- TCP chaos form state ---

interface TcpFormState {
  host: string;
  latencyMs: string;
  bandwidthBytesPerSec: string;
  down: boolean;
  resetPeer: boolean;
  slowClose: boolean;
  timeout: boolean;
  slicerChunkSize: string;
  limitDataBytes: string;
  ttlMs: string;
}

const EMPTY_TCP_FORM: TcpFormState = {
  host: '',
  latencyMs: '',
  bandwidthBytesPerSec: '',
  down: false,
  resetPeer: false,
  slowClose: false,
  timeout: false,
  slicerChunkSize: '',
  limitDataBytes: '',
  ttlMs: '',
};

function buildTcpChaosProfile(form: TcpFormState): TcpChaosProfileDTO {
  const profile: TcpChaosProfileDTO = {};
  const latMs = num(form.latencyMs);
  if (latMs != null) profile.latencyMs = latMs;
  const bw = num(form.bandwidthBytesPerSec);
  if (bw != null) profile.bandwidthBytesPerSec = bw;
  if (form.down) profile.down = true;
  if (form.resetPeer) profile.resetPeer = true;
  if (form.slowClose) profile.slowClose = true;
  if (form.timeout) profile.timeout = true;
  const slicer = num(form.slicerChunkSize);
  if (slicer != null) profile.slicerChunkSize = slicer;
  const limit = num(form.limitDataBytes);
  if (limit != null) profile.limitDataBytes = limit;
  return profile;
}

function validateTcpForm(form: TcpFormState): string | null {
  if (form.host.trim() === '') return 'Host is required';
  const profile = buildTcpChaosProfile(form);
  if (summarizeTcpChaosProfile(profile).length === 0) {
    return 'Set at least one fault (latency, bandwidth, down, reset, etc.)';
  }
  const ttl = num(form.ttlMs);
  if (ttl != null && (!Number.isInteger(ttl) || ttl < 1)) return 'TTL must be a whole number of milliseconds >= 1';
  return null;
}

const SERVING_STATUSES: ServingStatus[] = ['SERVING', 'NOT_SERVING', 'UNKNOWN', 'SERVICE_UNKNOWN'];

// --- gRPC fault injection form state ---

const GRPC_STATUS_CODES = [
  'OK', 'CANCELLED', 'UNKNOWN', 'INVALID_ARGUMENT', 'DEADLINE_EXCEEDED',
  'NOT_FOUND', 'ALREADY_EXISTS', 'PERMISSION_DENIED', 'RESOURCE_EXHAUSTED',
  'FAILED_PRECONDITION', 'ABORTED', 'OUT_OF_RANGE', 'UNIMPLEMENTED',
  'INTERNAL', 'UNAVAILABLE', 'DATA_LOSS', 'UNAUTHENTICATED',
] as const;

interface GrpcChaosFormState {
  service: string;
  errorStatusCode: string;
  errorProbability: string;
  latencyMs: string;
  quotaName: string;
  quotaLimit: string;
  quotaWindowMillis: string;
  ttlMs: string;
}

const EMPTY_GRPC_CHAOS_FORM: GrpcChaosFormState = {
  service: '',
  errorStatusCode: 'UNAVAILABLE',
  errorProbability: '',
  latencyMs: '',
  quotaName: '',
  quotaLimit: '',
  quotaWindowMillis: '',
  ttlMs: '',
};

function buildGrpcChaosProfile(form: GrpcChaosFormState): GrpcChaosProfileDTO {
  const profile: GrpcChaosProfileDTO = {};
  if (form.errorStatusCode) profile.errorStatusCode = form.errorStatusCode;
  const errorProbability = num(form.errorProbability);
  if (errorProbability != null) profile.errorProbability = errorProbability;
  const latencyMs = num(form.latencyMs);
  if (latencyMs != null) profile.latencyMs = latencyMs;
  const quotaName = form.quotaName.trim();
  if (quotaName) profile.quotaName = quotaName;
  const quotaLimit = num(form.quotaLimit);
  if (quotaLimit != null) profile.quotaLimit = quotaLimit;
  const quotaWindowMillis = num(form.quotaWindowMillis);
  if (quotaWindowMillis != null) profile.quotaWindowMillis = quotaWindowMillis;
  return profile;
}

function validateGrpcChaosForm(form: GrpcChaosFormState): string | null {
  if (form.service.trim() === '') return 'Service is required';
  const profile = buildGrpcChaosProfile(form);
  if (summarizeGrpcChaosProfile(profile).length === 0) {
    return 'Set at least one fault (error code, latency, or quota)';
  }
  const ep = num(form.errorProbability);
  if (ep != null && (ep < 0 || ep > 1)) return 'Error probability must be between 0 and 1';
  const latencyMs = num(form.latencyMs);
  if (latencyMs != null && latencyMs < 0) return 'Latency must be 0 or greater';
  const ttl = num(form.ttlMs);
  if (ttl != null && (!Number.isInteger(ttl) || ttl < 1)) return 'TTL must be a whole number of milliseconds >= 1';
  return null;
}

function servingStatusColor(status: ServingStatus): 'success' | 'error' | 'default' | 'warning' {
  switch (status) {
    case 'SERVING': return 'success';
    case 'NOT_SERVING': return 'error';
    case 'UNKNOWN': return 'default';
    case 'SERVICE_UNKNOWN': return 'warning';
  }
}

export default function ServiceChaosPanel({ connectionParams }: ServiceChaosPanelProps) {
  const [data, setData] = useState<ServiceChaosResponse>({ services: {} });
  const [polledAt, setPolledAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);

  // Edit inline form state
  const [editingHost, setEditingHost] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<EditFormState>({ errorStatus: '', errorProbability: '', dropProbability: '', latencyMs: '' });

  // HTTP service chaos section expand state (the primary section — open by default,
  // but collapsible for consistency with the gRPC and TCP sections).
  const [httpExpanded, setHttpExpanded] = useState(true);

  // gRPC health state
  const [grpcHealth, setGrpcHealthState] = useState<Record<string, ServingStatus>>({});
  const [grpcExpanded, setGrpcExpanded] = useState(false);
  const [grpcNewService, setGrpcNewService] = useState('');
  const [grpcNewStatus, setGrpcNewStatus] = useState<ServingStatus>('NOT_SERVING');

  // TCP chaos state
  const [tcpExpanded, setTcpExpanded] = useState(false);
  const [tcpData, setTcpData] = useState<TcpChaosResponse>({ hosts: {} });
  const [tcpForm, setTcpForm] = useState<TcpFormState>(EMPTY_TCP_FORM);

  // gRPC fault injection chaos state
  const [grpcChaosExpanded, setGrpcChaosExpanded] = useState(false);
  const [grpcChaosData, setGrpcChaosData] = useState<GrpcChaosResponse>({ services: {} });
  const [grpcChaosForm, setGrpcChaosForm] = useState<GrpcChaosFormState>(EMPTY_GRPC_CHAOS_FORM);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Poll the registry on an interval.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const response = await fetchServiceChaos(connectionParams, controller.signal);
        if (cancelled) return;
        setData(response);
        setPolledAt(Date.now());
        setLoadError(null);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setLoadError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick]);

  // Tick once a second so TTL countdowns update between polls.
  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(interval);
  }, []);

  // Fetch gRPC health on mount (so the collapsed header chip shows the real count),
  // then keep polling only while the section is expanded.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetchGrpcHealth(connectionParams, controller.signal);
        if (!cancelled) setGrpcHealthState(result);
      } catch {
        // ignore
      } finally {
        if (!cancelled && grpcExpanded) timer = setTimeout(() => void poll(), 10000);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, grpcExpanded, refreshTick]);

  // Fetch TCP chaos on mount (so the collapsed header chip shows the real count),
  // then keep polling only while the section is expanded.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetchTcpChaos(connectionParams, controller.signal);
        if (!cancelled) setTcpData(result);
      } catch {
        // ignore
      } finally {
        if (!cancelled && tcpExpanded) timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, tcpExpanded, refreshTick]);

  // Fetch gRPC fault injection chaos on mount (so the collapsed header chip shows the real count),
  // then keep polling only while the section is expanded.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetchGrpcChaos(connectionParams, controller.signal);
        if (!cancelled) setGrpcChaosData(result);
      } catch {
        // ignore
      } finally {
        if (!cancelled && grpcChaosExpanded) timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, grpcChaosExpanded, refreshTick]);

  const hosts = useMemo(() => Object.keys(data.services).sort(), [data.services]);

  // Remaining TTL for a host, decremented client-side since the last poll.
  const remainingTtl = (host: string): number | undefined => {
    const atPoll = data.ttlRemainingMillis?.[host];
    if (atPoll == null) return undefined;
    return Math.max(0, atPoll - (now - polledAt));
  };

  const runAction = useCallback(
    async (action: () => Promise<void>) => {
      setBusy(true);
      setActionError(null);
      try {
        await action();
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  const handleRegister = useCallback(() => {
    const validationError = validateForm(form);
    if (validationError !== null) {
      setActionError(validationError);
      return;
    }
    const host = form.host.trim();
    const profile = buildChaosProfile(form);
    const ttl = num(form.ttlMs);
    void runAction(async () => {
      await registerServiceChaos(connectionParams, host, profile, ttl);
      setForm(EMPTY_FORM);
    });
  }, [connectionParams, form, runAction]);

  const setField = (field: keyof FormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const handleStartEdit = useCallback((host: string) => {
    const profile = data.services[host] ?? {};
    setEditForm({
      errorStatus: profile.errorStatus != null ? String(profile.errorStatus) : '',
      errorProbability: profile.errorProbability != null ? String(profile.errorProbability) : '',
      dropProbability: profile.dropConnectionProbability != null ? String(profile.dropConnectionProbability) : '',
      latencyMs: profile.latency?.value != null ? String(profile.latency.value) : '',
    });
    setEditingHost(host);
  }, [data.services]);

  const handleCancelEdit = useCallback(() => {
    setEditingHost(null);
  }, []);

  const handleApplyEdit = useCallback(() => {
    if (!editingHost) return;
    const partial: Partial<HttpChaosProfileDTO> = {};
    const errorStatus = num(editForm.errorStatus);
    if (errorStatus != null) {
      partial.errorStatus = errorStatus;
      const ep = num(editForm.errorProbability);
      if (ep != null) partial.errorProbability = ep;
    }
    const dp = num(editForm.dropProbability);
    if (dp != null) partial.dropConnectionProbability = dp;
    const lm = num(editForm.latencyMs);
    if (lm != null) partial.latency = { timeUnit: 'MILLISECONDS', value: lm };

    const host = editingHost;
    void runAction(async () => {
      await patchServiceChaos(connectionParams, host, partial);
      setEditingHost(null);
    });
  }, [connectionParams, editingHost, editForm, runAction]);

  const setEditField = (field: keyof EditFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setEditForm((prev) => ({ ...prev, [field]: e.target.value }));

  const handleSetGrpcHealth = useCallback(() => {
    if (!grpcNewService.trim()) return;
    void runAction(async () => {
      await setGrpcHealth(connectionParams, grpcNewService.trim(), grpcNewStatus);
      setGrpcNewService('');
    });
  }, [connectionParams, grpcNewService, grpcNewStatus, runAction]);

  const handleResetGrpcHealth = useCallback((service: string) => {
    void runAction(async () => {
      await resetGrpcHealth(connectionParams, service);
    });
  }, [connectionParams, runAction]);

  // Null-safe: a control-plane response with an unexpected/missing shape must never
  // crash the panel (Object.keys(undefined) throws). Default to an empty set.
  const grpcServices = useMemo(() => Object.keys(grpcHealth ?? {}).sort(), [grpcHealth]);

  // TCP chaos helpers
  const tcpHosts = useMemo(() => Object.keys(tcpData?.hosts ?? {}).sort(), [tcpData?.hosts]);

  const tcpRemainingTtl = (host: string): number | undefined => {
    const atPoll = tcpData.ttlRemainingMillis?.[host];
    if (atPoll == null) return undefined;
    return Math.max(0, atPoll - (now - polledAt));
  };

  const setTcpField = (field: keyof TcpFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setTcpForm((prev) => ({ ...prev, [field]: e.target.value }));

  const setTcpToggle = (field: keyof TcpFormState) => (_e: ChangeEvent<HTMLInputElement>, checked: boolean) =>
    setTcpForm((prev) => ({ ...prev, [field]: checked }));

  const handleRegisterTcp = useCallback(() => {
    const validationError = validateTcpForm(tcpForm);
    if (validationError !== null) {
      setActionError(validationError);
      return;
    }
    const host = tcpForm.host.trim();
    const profile = buildTcpChaosProfile(tcpForm);
    const ttl = num(tcpForm.ttlMs);
    void runAction(async () => {
      await registerTcpChaos(connectionParams, host, profile, ttl);
      setTcpForm(EMPTY_TCP_FORM);
    });
  }, [connectionParams, tcpForm, runAction]);

  // gRPC fault injection chaos helpers
  const grpcChaosServices = useMemo(() => Object.keys(grpcChaosData?.services ?? {}).sort(), [grpcChaosData?.services]);

  const grpcChaosRemainingTtl = (service: string): number | undefined => {
    const atPoll = grpcChaosData.ttlRemainingMillis?.[service];
    if (atPoll == null) return undefined;
    return Math.max(0, atPoll - (now - polledAt));
  };

  const setGrpcChaosField = (field: keyof GrpcChaosFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setGrpcChaosForm((prev) => ({ ...prev, [field]: e.target.value }));

  const handleRegisterGrpcChaos = useCallback(() => {
    const validationError = validateGrpcChaosForm(grpcChaosForm);
    if (validationError !== null) {
      setActionError(validationError);
      return;
    }
    const service = grpcChaosForm.service.trim();
    const profile = buildGrpcChaosProfile(grpcChaosForm);
    const ttl = num(grpcChaosForm.ttlMs);
    void runAction(async () => {
      await registerGrpcChaos(connectionParams, service, profile, ttl);
      setGrpcChaosForm(EMPTY_GRPC_CHAOS_FORM);
    });
  }, [connectionParams, grpcChaosForm, runAction]);

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Service Chaos
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh service chaos">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {loadError && (
        <Alert severity="error" sx={{ mb: 1.5 }} action={
          <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
        }>
          <AlertTitle>Could not load service chaos</AlertTitle>
          {loadError}
        </Alert>
      )}

      {actionError && (
        <Alert severity="warning" sx={{ mb: 1.5 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}

      {/* HTTP Service Chaos */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }}
          onClick={() => setHttpExpanded((v) => !v)}
        >
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            HTTP Service Chaos
          </Typography>
          <Chip size="small" label={`${hosts.length} active`} color={hosts.length > 0 ? 'warning' : 'default'} variant="outlined" />
          <Box sx={{ flex: 1 }} />
          <Tooltip title="Clear all service-scoped chaos">
            <span>
              <Button
                size="small"
                color="error"
                startIcon={<DeleteSweepIcon fontSize="small" />}
                disabled={busy || hosts.length === 0}
                onClick={(e) => {
                  e.stopPropagation();
                  void runAction(() => clearServiceChaos(connectionParams));
                }}
              >
                Clear all
              </Button>
            </span>
          </Tooltip>
          <IconButton size="small" aria-label={httpExpanded ? 'Collapse HTTP chaos' : 'Expand HTTP chaos'}>
            {httpExpanded ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
          </IconButton>
        </Box>
        <Collapse in={httpExpanded}>
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Register one HTTP chaos profile per upstream host; it is applied to every matched forward to that host.
              Add a TTL to auto-revert the fault after a bounded window (a dead-man&apos;s switch).
            </Typography>

            {/* Register form */}
            <Paper variant="outlined" sx={{ p: 1, mb: 1 }}>
              <Typography variant="caption" color="text.secondary">Register chaos for a host</Typography>
              <Box sx={{ display: 'flex', gap: 1, mt: 0.75, flexWrap: 'wrap', alignItems: 'flex-start' }}>
                <TextField size="small" label="Host" placeholder="upstream.svc" value={form.host} onChange={setField('host')} sx={{ minWidth: 180 }} />
                <TextField size="small" label="Error status" placeholder="503" value={form.errorStatus} onChange={setField('errorStatus')} sx={{ width: 110 }} />
                <TextField size="small" label="Error prob" placeholder="0.5" value={form.errorProbability} onChange={setField('errorProbability')} sx={{ width: 100 }} />
                <TextField size="small" label="Drop prob" placeholder="0.2" value={form.dropProbability} onChange={setField('dropProbability')} sx={{ width: 100 }} />
                <TextField size="small" label="Latency ms" placeholder="250" value={form.latencyMs} onChange={setField('latencyMs')} sx={{ width: 100 }} />
                <TextField size="small" label="TTL ms" placeholder="60000" value={form.ttlMs} onChange={setField('ttlMs')} sx={{ width: 110 }} />
                <Button variant="contained" size="small" disabled={busy} onClick={handleRegister} sx={{ mt: 0.25 }}>
                  Register
                </Button>
              </Box>
            </Paper>

            {/* Active registrations */}
            {hosts.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                No service-scoped chaos registered.
              </Typography>
            ) : (
              <Box>
                {hosts.map((host) => {
                  const ttl = remainingTtl(host);
                  const isEditing = editingHost === host;
                  return (
                    <Box key={host}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.75, borderBottom: '1px solid', borderColor: 'divider', flexWrap: 'wrap' }}>
                        <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 160 }}>{host}</Typography>
                        <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', flex: 1 }}>
                          {summarizeChaosProfile(data.services[host] ?? {}).map((part) => (
                            <Chip key={part} size="small" label={part} variant="outlined" />
                          ))}
                        </Box>
                        {ttl != null && (
                          <Chip size="small" color="warning" label={`auto-revert in ${formatTtl(ttl)}`} />
                        )}
                        <Tooltip title="Edit chaos profile for this host">
                          <span>
                            <IconButton
                              size="small"
                              aria-label={`Edit chaos for ${host}`}
                              disabled={busy}
                              onClick={() => isEditing ? handleCancelEdit() : handleStartEdit(host)}
                            >
                              <EditIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Tooltip title="Remove chaos for this host">
                          <span>
                            <IconButton
                              size="small"
                              aria-label={`Remove chaos for ${host}`}
                              disabled={busy}
                              onClick={() => void runAction(() => removeServiceChaos(connectionParams, host))}
                            >
                              <DeleteIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                      </Box>
                      {isEditing && (
                        <Box sx={{ display: 'flex', gap: 1, py: 0.75, pl: 2, flexWrap: 'wrap', alignItems: 'flex-start', bgcolor: 'action.hover', borderBottom: '1px solid', borderColor: 'divider' }}>
                          <TextField size="small" label="Error status" value={editForm.errorStatus} onChange={setEditField('errorStatus')} sx={{ width: 110 }} />
                          <TextField size="small" label="Error prob" value={editForm.errorProbability} onChange={setEditField('errorProbability')} sx={{ width: 100 }} />
                          <TextField size="small" label="Drop prob" value={editForm.dropProbability} onChange={setEditField('dropProbability')} sx={{ width: 100 }} />
                          <TextField size="small" label="Latency ms" value={editForm.latencyMs} onChange={setEditField('latencyMs')} sx={{ width: 100 }} />
                          <Button size="small" variant="contained" disabled={busy} onClick={handleApplyEdit}>Apply</Button>
                          <Button size="small" onClick={handleCancelEdit}>Cancel</Button>
                        </Box>
                      )}
                    </Box>
                  );
                })}
              </Box>
            )}
          </Box>
        </Collapse>
      </Paper>

      {/* gRPC Health Chaos */}
      <Paper variant="outlined" sx={{ p: 1.25 }}>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }}
          onClick={() => setGrpcExpanded((v) => !v)}
        >
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            gRPC Health Chaos
          </Typography>
          <Chip size="small" label={`${grpcServices.length} services`} variant="outlined" />
          <Box sx={{ flex: 1 }} />
          <IconButton size="small" aria-label={grpcExpanded ? 'Collapse gRPC health' : 'Expand gRPC health'}>
            {grpcExpanded ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
          </IconButton>
        </Box>
        <Collapse in={grpcExpanded}>
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Force a service&apos;s gRPC health-check response (e.g. NOT_SERVING) to simulate an
              unhealthy or degraded service — exercising how clients and orchestrators
              (Kubernetes readiness/liveness probes) react to a failing dependency.
            </Typography>
            {/* Set status form */}
            <Box sx={{ display: 'flex', gap: 1, mb: 1, flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <TextField
                size="small"
                label="Service name"
                placeholder="my.grpc.Service"
                value={grpcNewService}
                onChange={(e: ChangeEvent<HTMLInputElement>) => setGrpcNewService(e.target.value)}
                sx={{ minWidth: 200 }}
              />
              <Select
                size="small"
                value={grpcNewStatus}
                onChange={(e) => setGrpcNewStatus(e.target.value as ServingStatus)}
                sx={{ minWidth: 160 }}
              >
                {SERVING_STATUSES.map((s) => (
                  <MenuItem key={s} value={s}>{s}</MenuItem>
                ))}
              </Select>
              <Button size="small" variant="contained" disabled={busy || !grpcNewService.trim()} onClick={handleSetGrpcHealth}>
                Set Status
              </Button>
            </Box>
            {/* Service list */}
            {grpcServices.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                No gRPC health overrides set.
              </Typography>
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Service</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {grpcServices.map((svc) => (
                      <TableRow key={svc}>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>{svc}</Typography>
                        </TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            label={grpcHealth[svc]}
                            color={servingStatusColor(grpcHealth[svc]!)}
                            sx={{ height: 20, fontSize: '0.65rem' }}
                          />
                        </TableCell>
                        <TableCell align="right">
                          <Tooltip title="Reset health override">
                            <span>
                              <IconButton
                                size="small"
                                aria-label={`Reset health for ${svc}`}
                                disabled={busy}
                                onClick={() => handleResetGrpcHealth(svc)}
                              >
                                <RestoreIcon fontSize="small" />
                              </IconButton>
                            </span>
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Box>
        </Collapse>
      </Paper>

      {/* gRPC Fault Injection */}
      <Paper variant="outlined" sx={{ p: 1.25, mt: 1.5 }}>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }}
          onClick={() => setGrpcChaosExpanded((v) => !v)}
        >
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            gRPC Fault Injection
          </Typography>
          <Chip size="small" label={`${grpcChaosServices.length} services`} color={grpcChaosServices.length > 0 ? 'warning' : 'default'} variant="outlined" />
          <Box sx={{ flex: 1 }} />
          <Tooltip title="Clear gRPC fault injection chaos">
            <span>
              <Button
                size="small"
                color="error"
                startIcon={<DeleteSweepIcon fontSize="small" />}
                disabled={busy || grpcChaosServices.length === 0}
                onClick={(e) => {
                  e.stopPropagation();
                  void runAction(() => clearGrpcChaos(connectionParams));
                }}
              >
                Clear gRPC
              </Button>
            </span>
          </Tooltip>
          <IconButton size="small" aria-label={grpcChaosExpanded ? 'Collapse gRPC fault injection' : 'Expand gRPC fault injection'}>
            {grpcChaosExpanded ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
          </IconButton>
        </Box>
        <Collapse in={grpcChaosExpanded} unmountOnExit>
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Inject gRPC status errors (UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, &hellip;) and
              latency on matched RPC calls &mdash; distinct from the health-check status above.
            </Typography>

            {/* gRPC Chaos Register form */}
            <Paper variant="outlined" sx={{ p: 1, mb: 1 }}>
              <Typography variant="caption" color="text.secondary">Register gRPC chaos for a service</Typography>
              <Box sx={{ display: 'flex', gap: 1, mt: 0.75, flexWrap: 'wrap', alignItems: 'flex-start' }}>
                <TextField size="small" label="Service" placeholder="my.grpc.Service" value={grpcChaosForm.service} onChange={setGrpcChaosField('service')} sx={{ minWidth: 200 }} />
                <Select
                  size="small"
                  value={grpcChaosForm.errorStatusCode}
                  onChange={(e) => setGrpcChaosForm((prev) => ({ ...prev, errorStatusCode: e.target.value }))}
                  sx={{ minWidth: 180 }}
                >
                  {GRPC_STATUS_CODES.map((code) => (
                    <MenuItem key={code} value={code}>{code}</MenuItem>
                  ))}
                </Select>
                <TextField size="small" label="Error prob" placeholder="0.5" value={grpcChaosForm.errorProbability} onChange={setGrpcChaosField('errorProbability')} sx={{ width: 100 }} />
                <TextField size="small" label="Latency ms" placeholder="200" value={grpcChaosForm.latencyMs} onChange={setGrpcChaosField('latencyMs')} sx={{ width: 100 }} />
                <TextField size="small" label="TTL ms" placeholder="60000" value={grpcChaosForm.ttlMs} onChange={setGrpcChaosField('ttlMs')} sx={{ width: 110 }} />
              </Box>
              <Box sx={{ display: 'flex', gap: 1, mt: 0.5, flexWrap: 'wrap', alignItems: 'flex-start' }}>
                <TextField size="small" label="Quota name" placeholder="rpc-quota" value={grpcChaosForm.quotaName} onChange={setGrpcChaosField('quotaName')} sx={{ width: 140 }} />
                <TextField size="small" label="Quota limit" placeholder="100" value={grpcChaosForm.quotaLimit} onChange={setGrpcChaosField('quotaLimit')} sx={{ width: 100 }} />
                <TextField size="small" label="Quota window ms" placeholder="60000" value={grpcChaosForm.quotaWindowMillis} onChange={setGrpcChaosField('quotaWindowMillis')} sx={{ width: 130 }} />
                <Button variant="contained" size="small" disabled={busy} onClick={handleRegisterGrpcChaos} sx={{ ml: 'auto', mt: 0.25 }}>
                  Register
                </Button>
              </Box>
            </Paper>

            {/* gRPC Chaos Active registrations */}
            {grpcChaosServices.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                No gRPC fault injection chaos registered.
              </Typography>
            ) : (
              <Box>
                {grpcChaosServices.map((service) => {
                  const ttl = grpcChaosRemainingTtl(service);
                  return (
                    <Box key={service} sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.75, borderBottom: '1px solid', borderColor: 'divider', flexWrap: 'wrap' }}>
                      <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 200 }}>{service}</Typography>
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', flex: 1 }}>
                        {summarizeGrpcChaosProfile(grpcChaosData.services[service] ?? {}).map((part) => (
                          <Chip key={part} size="small" label={part} variant="outlined" />
                        ))}
                      </Box>
                      {ttl != null && (
                        <Chip size="small" color="warning" label={`auto-revert in ${formatTtl(ttl)}`} />
                      )}
                      <Tooltip title="Remove gRPC chaos for this service">
                        <span>
                          <IconButton
                            size="small"
                            aria-label={`Remove gRPC chaos for ${service}`}
                            disabled={busy}
                            onClick={() => void runAction(() => removeGrpcChaos(connectionParams, service))}
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </span>
                      </Tooltip>
                    </Box>
                  );
                })}
              </Box>
            )}
          </Box>
        </Collapse>
      </Paper>

      {/* TCP-Layer Chaos */}
      <Paper variant="outlined" sx={{ p: 1.25, mt: 1.5 }}>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }}
          onClick={() => setTcpExpanded((v) => !v)}
        >
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            TCP-Layer Chaos
          </Typography>
          <Chip size="small" label={`${tcpHosts.length} hosts`} color={tcpHosts.length > 0 ? 'warning' : 'default'} variant="outlined" />
          <Box sx={{ flex: 1 }} />
          <Tooltip title="Clear all TCP chaos">
            <span>
              <Button
                size="small"
                color="error"
                startIcon={<DeleteSweepIcon fontSize="small" />}
                disabled={busy || tcpHosts.length === 0}
                onClick={(e) => {
                  e.stopPropagation();
                  void runAction(() => clearTcpChaos(connectionParams));
                }}
              >
                Clear TCP
              </Button>
            </span>
          </Tooltip>
          <IconButton size="small" aria-label={tcpExpanded ? 'Collapse TCP chaos' : 'Expand TCP chaos'}>
            {tcpExpanded ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
          </IconButton>
        </Box>
        <Collapse in={tcpExpanded} unmountOnExit>
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Register one TCP chaos profile per upstream host; faults are applied at the raw
              byte level before HTTP decoding (latency, bandwidth, reset, timeout, etc.).
            </Typography>

            {/* TCP Register form */}
            <Paper variant="outlined" sx={{ p: 1, mb: 1 }}>
              <Typography variant="caption" color="text.secondary">Register TCP chaos for a host</Typography>
              <Box sx={{ display: 'flex', gap: 1, mt: 0.75, flexWrap: 'wrap', alignItems: 'flex-start' }}>
                <TextField size="small" label="Host" placeholder="upstream.svc" value={tcpForm.host} onChange={setTcpField('host')} sx={{ minWidth: 160 }} />
                <TextField size="small" label="Latency ms" placeholder="200" value={tcpForm.latencyMs} onChange={setTcpField('latencyMs')} sx={{ width: 100 }} />
                <TextField size="small" label="Bandwidth B/s" placeholder="1024" value={tcpForm.bandwidthBytesPerSec} onChange={setTcpField('bandwidthBytesPerSec')} sx={{ width: 120 }} />
                <TextField size="small" label="Slicer bytes" placeholder="64" value={tcpForm.slicerChunkSize} onChange={setTcpField('slicerChunkSize')} sx={{ width: 100 }} />
                <TextField size="small" label="Limit bytes" placeholder="4096" value={tcpForm.limitDataBytes} onChange={setTcpField('limitDataBytes')} sx={{ width: 100 }} />
                <TextField size="small" label="TTL ms" placeholder="60000" value={tcpForm.ttlMs} onChange={setTcpField('ttlMs')} sx={{ width: 100 }} />
              </Box>
              <Box sx={{ display: 'flex', gap: 1.5, mt: 0.5, flexWrap: 'wrap', alignItems: 'center' }}>
                <FormControlLabel control={<Switch size="small" checked={tcpForm.down} onChange={setTcpToggle('down')} />} label="Down" />
                <FormControlLabel control={<Switch size="small" checked={tcpForm.resetPeer} onChange={setTcpToggle('resetPeer')} />} label="Reset peer" />
                <FormControlLabel control={<Switch size="small" checked={tcpForm.slowClose} onChange={setTcpToggle('slowClose')} />} label="Slow close" />
                <FormControlLabel control={<Switch size="small" checked={tcpForm.timeout} onChange={setTcpToggle('timeout')} />} label="Timeout" />
                <Button variant="contained" size="small" disabled={busy} onClick={handleRegisterTcp} sx={{ ml: 'auto' }}>
                  Register
                </Button>
              </Box>
            </Paper>

            {/* TCP Active registrations */}
            {tcpHosts.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                No TCP-layer chaos registered.
              </Typography>
            ) : (
              <Box>
                {tcpHosts.map((host) => {
                  const ttl = tcpRemainingTtl(host);
                  return (
                    <Box key={host} sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.75, borderBottom: '1px solid', borderColor: 'divider', flexWrap: 'wrap' }}>
                      <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 160 }}>{host}</Typography>
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', flex: 1 }}>
                        {summarizeTcpChaosProfile(tcpData.hosts[host] ?? {}).map((part) => (
                          <Chip key={part} size="small" label={part} variant="outlined" />
                        ))}
                      </Box>
                      {ttl != null && (
                        <Chip size="small" color="warning" label={`auto-revert in ${formatTtl(ttl)}`} />
                      )}
                      <Tooltip title="Remove TCP chaos for this host">
                        <span>
                          <IconButton
                            size="small"
                            aria-label={`Remove TCP chaos for ${host}`}
                            disabled={busy}
                            onClick={() => void runAction(() => removeTcpChaos(connectionParams, host))}
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </span>
                      </Tooltip>
                    </Box>
                  );
                })}
              </Box>
            )}
          </Box>
        </Collapse>
      </Paper>
    </Box>
  );
}
