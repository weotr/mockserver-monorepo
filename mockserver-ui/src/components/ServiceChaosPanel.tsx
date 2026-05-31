import { useCallback, useEffect, useMemo, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import DeleteIcon from '@mui/icons-material/Delete';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import RefreshIcon from '@mui/icons-material/Refresh';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchServiceChaos,
  registerServiceChaos,
  removeServiceChaos,
  clearServiceChaos,
  summarizeChaosProfile,
  formatTtl,
  type HttpChaosProfileDTO,
  type ServiceChaosResponse,
} from '../lib/serviceChaos';

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

export default function ServiceChaosPanel({ connectionParams }: ServiceChaosPanelProps) {
  const [data, setData] = useState<ServiceChaosResponse>({ services: {} });
  const [polledAt, setPolledAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);

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

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Service Chaos
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
              onClick={() => void runAction(() => clearServiceChaos(connectionParams))}
            >
              Clear all
            </Button>
          </span>
        </Tooltip>
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh service chaos">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Register one HTTP chaos profile per upstream host; it is applied to every matched forward to that host.
        Add a TTL to auto-revert the fault after a bounded window (a dead-man&apos;s switch).
      </Typography>

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

      {/* Register form */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
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
      <Paper variant="outlined" sx={{ p: 1.25 }}>
        <Typography variant="caption" color="text.secondary">Active registrations</Typography>
        {hosts.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            No service-scoped chaos registered.
          </Typography>
        ) : (
          <Box sx={{ mt: 0.5 }}>
            {hosts.map((host) => {
              const ttl = remainingTtl(host);
              return (
                <Box key={host} sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.75, borderBottom: '1px solid', borderColor: 'divider', flexWrap: 'wrap' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 160 }}>{host}</Typography>
                  <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', flex: 1 }}>
                    {summarizeChaosProfile(data.services[host] ?? {}).map((part) => (
                      <Chip key={part} size="small" label={part} variant="outlined" />
                    ))}
                  </Box>
                  {ttl != null && (
                    <Chip size="small" color="warning" label={`auto-revert in ${formatTtl(ttl)}`} />
                  )}
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
              );
            })}
          </Box>
        )}
      </Paper>
    </Box>
  );
}
