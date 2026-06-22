import { useCallback, useEffect, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Card from '@mui/material/Card';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Skeleton from '@mui/material/Skeleton';
import Table from '@mui/material/Table';
import TableHead from '@mui/material/TableHead';
import TableBody from '@mui/material/TableBody';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import RefreshIcon from '@mui/icons-material/Refresh';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DownloadIcon from '@mui/icons-material/Download';
import { useDashboardStore } from '../store';
import { groupBySession } from '../lib/sessionGrouping';
import {
  fetchOptimisationReport,
  fetchOptimisationBrief,
  sortSignalsBySeverity,
  type OptimisationReport,
  type OptimisationSignal,
  type SignalSeverity,
} from '../lib/optimisation';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';
import { monospaceFontFamily, transitions } from '../theme';

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

const ALL_SESSIONS = '__ALL__';

function formatCost(usd: number): string {
  if (!Number.isFinite(usd)) return '—';
  if (usd === 0) return '$0.00';
  if (usd < 0.01) return `$${usd.toFixed(6)}`;
  if (usd < 1) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

function formatMs(ms: number): string {
  if (!Number.isFinite(ms)) return '—';
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.round(ms)} ms`;
}

function severityColor(severity: SignalSeverity | string): 'error' | 'warning' | 'info' | 'default' {
  switch (severity) {
    case 'HIGH': return 'error';
    case 'MEDIUM': return 'warning';
    case 'LOW': return 'info';
    default: return 'default';
  }
}

// ---------------------------------------------------------------------------
// Session picker options — built from the same grouping the dashboard already
// uses (sessionGrouping.ts), so the keys the user picks line up with what the
// server groups by. The default option requests a report over ALL traffic.
// ---------------------------------------------------------------------------

interface SessionOption {
  /** Value sent as the `session` query param (empty for "all"). */
  value: string;
  label: string;
}

function useSessionOptions(): SessionOption[] {
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);

  return useMemo(() => {
    const all = [...proxiedRequests, ...recordedRequests];
    const sessions = groupBySession(all, activeExpectations);
    const options: SessionOption[] = [{ value: ALL_SESSIONS, label: 'All captured LLM traffic' }];
    const seen = new Set<string>();
    for (const session of sessions) {
      // v1 optimisation groups proxied LLM traffic by upstream host. Isolation-
      // scoped (mocked-conversation) sessions are not cost-relevant and the
      // server groups only by host, so we offer host-grouped sessions only —
      // otherwise selecting an isolation-scoped option would silently return an
      // empty report. See docs/code/llm-mocking.md (optimisation export, v1).
      if (session.scenarioName !== '<unscoped>') continue;
      const key = session.isolationKey;
      if (!key || key === '<unscoped>' || seen.has(key)) continue;
      seen.add(key);
      options.push({ value: key, label: key });
    }
    return options;
  }, [proxiedRequests, recordedRequests, activeExpectations]);
}

// ---------------------------------------------------------------------------
// Hero cards
// ---------------------------------------------------------------------------

interface HeroCardProps {
  label: string;
  value: string;
}

function HeroCard({ label, value }: HeroCardProps) {
  return (
    <Card
      elevation={1}
      sx={{
        p: 1.25,
        transition: transitions.forProps(['box-shadow', 'transform']),
        '&:hover': {
          boxShadow: (t) => t.shadows[4],
          transform: 'translateY(-1px)',
        },
      }}
    >
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, mt: 0.25 }}>{value}</Typography>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Signals panel
// ---------------------------------------------------------------------------

function SignalCard({ signal }: { signal: OptimisationSignal }) {
  return (
    <Paper variant="outlined" sx={{ p: 1.25, mb: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
        <Chip
          size="small"
          label={signal.severity}
          color={severityColor(signal.severity)}
          variant="filled"
          sx={{ height: 20, fontSize: '0.65rem', fontWeight: 700 }}
        />
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>{signal.title}</Typography>
        <Box sx={{ flex: 1 }} />
        {signal.estimatedSavingUsd != null && (
          <Chip
            size="small"
            color="success"
            variant="outlined"
            label={`save ~${formatCost(signal.estimatedSavingUsd)}`}
            sx={{ height: 20, fontSize: '0.65rem', fontFamily: monospaceFontFamily }}
          />
        )}
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>{signal.detail}</Typography>
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 0.5 }}>
        {signal.affectedCalls.length > 0 && (
          <Typography variant="caption" color="text.secondary">
            Affected calls: {signal.affectedCalls.join(', ')}
          </Typography>
        )}
        {signal.estimatedWastedInputTokens != null && (
          <Typography variant="caption" color="text.secondary">
            ~{signal.estimatedWastedInputTokens.toLocaleString()} wasted input tokens
          </Typography>
        )}
      </Box>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        Recommendation: <Box component="span" sx={{ fontWeight: 400 }}>{signal.recommendation}</Box>
      </Typography>
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// Main view
// ---------------------------------------------------------------------------

interface OptimiseViewProps {
  connectionParams: ConnectionParams;
}

type LoadState = 'loading' | 'ok' | 'error';

export default function OptimiseView({ connectionParams }: OptimiseViewProps) {
  const sessionOptions = useSessionOptions();
  const [selected, setSelected] = useState<string>(ALL_SESSIONS);
  const [report, setReport] = useState<OptimisationReport | null>(null);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<HumanError | null>(null);
  const [busyAction, setBusyAction] = useState<null | 'copy' | 'download'>(null);
  const [actionError, setActionError] = useState<HumanError | null>(null);
  const [copied, setCopied] = useState(false);

  // Map the picker selection to the query the lib expects (omit for "all").
  const query = useMemo(
    () => (selected === ALL_SESSIONS ? {} : { session: selected }),
    [selected],
  );

  // Runs the fetch and reconciles state. Sets the loading state synchronously so
  // the skeleton shows at once, then resolves to ok/error.
  const load = useCallback(
    (signal?: AbortSignal) => {
      setState('loading');
      setError(null);
      fetchOptimisationReport(connectionParams, query, signal)
        .then((r) => {
          if (signal?.aborted) return;
          setReport(r);
          setState('ok');
        })
        .catch((e) => {
          if (signal?.aborted) return;
          setReport(null);
          setError(humanizeError(e));
          setState('error');
        });
    },
    [connectionParams, query],
  );

  // Re-fetch on mount and whenever the selected session changes. `load` sets
  // loading state synchronously on purpose so the skeleton appears immediately.
  useEffect(() => {
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const handleCopy = useCallback(async () => {
    setBusyAction('copy');
    setActionError(null);
    try {
      const markdown = await fetchOptimisationBrief(connectionParams, query);
      await navigator.clipboard.writeText(markdown);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusyAction(null);
    }
  }, [connectionParams, query]);

  const handleDownload = useCallback(async () => {
    setBusyAction('download');
    setActionError(null);
    try {
      const res = await fetchOptimisationReport(connectionParams, query);
      const blob = new Blob([JSON.stringify(res, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      const suffix = selected === ALL_SESSIONS ? 'all' : selected.replace(/[^A-Za-z0-9._-]+/g, '_');
      anchor.download = `optimisation-report-${suffix}.json`;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusyAction(null);
    }
  }, [connectionParams, query, selected]);

  const totals = report?.totals;
  const hasReport = report != null;
  const isEmpty = hasReport && totals != null && totals.callCount === 0;
  const sortedSignals = useMemo(
    () => (report ? sortSignalsBySeverity(report.signals ?? []) : []),
    [report],
  );

  const avgLatencyMs = totals && totals.callCount > 0 ? totals.totalLatencyMs / totals.callCount : 0;

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      {/* Header + session picker */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>LLM Optimise</Typography>
        <Tooltip title="Captured LLM traffic, analysed for deterministic cost-saving opportunities. Export a brief to paste into any LLM.">
          <Chip size="small" label="LLM cost" variant="outlined" />
        </Tooltip>
        <Box sx={{ flex: 1 }} />
        <TextField
          select
          size="small"
          label="Session"
          value={selected}
          onChange={(e) => setSelected(e.target.value)}
          sx={{ minWidth: 240, '& .MuiInputBase-root': { fontSize: '0.8rem' } }}
          slotProps={{ htmlInput: { 'aria-label': 'Session' } }}
        >
          {sessionOptions.map((opt) => (
            <MenuItem key={opt.value} value={opt.value} sx={{ fontSize: '0.8rem' }}>
              {opt.label}
            </MenuItem>
          ))}
        </TextField>
        <Tooltip title="Refresh report">
          <IconButton size="small" onClick={() => load()} aria-label="Refresh report">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {state === 'error' && error && (
        <HumanErrorAlert error={error} sx={{ mb: 1.5 }} data-testid="optimise-error" />
      )}

      {actionError && (
        <HumanErrorAlert error={actionError} sx={{ mb: 1.5 }} data-testid="optimise-action-error" onClose={() => setActionError(null)} />
      )}

      {state === 'loading' && !report && (
        <Box data-testid="optimise-loading">
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 1, mb: 1.5 }}>
            {[0, 1, 2, 3, 4].map((i) => (
              <Card key={i} elevation={1} sx={{ p: 1.25 }}>
                <Skeleton variant="text" width="55%" height={14} />
                <Skeleton variant="text" width="70%" height={32} />
              </Card>
            ))}
          </Box>
        </Box>
      )}

      {isEmpty && (
        <Paper variant="outlined" sx={{ p: 3, textAlign: 'center' }}>
          <Typography variant="body2" color="text.secondary">No LLM traffic captured</Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
            Proxy LLM calls through MockServer, then return here to analyse them for cost-saving opportunities.
          </Typography>
        </Paper>
      )}

      {hasReport && totals && !isEmpty && (
        <>
          {/* Hero cards */}
          <Box
            sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 1, mb: 1.5 }}
            data-testid="optimise-hero"
          >
            <HeroCard
              label={totals.costIsEstimated ? 'Total cost (est.)' : 'Total cost'}
              value={formatCost(totals.estimatedCostUsd)}
            />
            <HeroCard label="Input tokens" value={totals.inputTokens.toLocaleString()} />
            <HeroCard label="Output tokens" value={totals.outputTokens.toLocaleString()} />
            <HeroCard label="Calls" value={totals.callCount.toLocaleString()} />
            <HeroCard label="Avg latency" value={formatMs(avgLatencyMs)} />
          </Box>

          {/* Provider / model summary + actions */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
            {(report.session.providers ?? []).map((p) => (
              <Chip key={`prov-${p}`} size="small" variant="outlined" label={p} sx={{ height: 20, fontSize: '0.65rem' }} />
            ))}
            {(report.session.models ?? []).map((m) => (
              <Chip key={`model-${m}`} size="small" variant="outlined" color="primary" label={m} sx={{ height: 20, fontSize: '0.65rem', fontFamily: monospaceFontFamily }} />
            ))}
            <Box sx={{ flex: 1 }} />
            <Button
              variant="outlined"
              size="small"
              startIcon={<ContentCopyIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={() => void handleCopy()}
              disabled={busyAction !== null}
            >
              {copied ? 'Copied!' : busyAction === 'copy' ? 'Copying…' : 'Copy optimisation brief'}
            </Button>
            <Button
              variant="contained"
              size="small"
              startIcon={<DownloadIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={() => void handleDownload()}
              disabled={busyAction !== null}
            >
              {busyAction === 'download' ? 'Downloading…' : 'Download bundle'}
            </Button>
          </Box>

          {/* Detected opportunities */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
              Detected opportunities {sortedSignals.length > 0 && `(${sortedSignals.length})`}
            </Typography>
            {sortedSignals.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No optimisation opportunities detected for this session.
              </Typography>
            ) : (
              <Box data-testid="optimise-signals">
                {sortedSignals.map((signal, i) => (
                  <SignalCard key={`${signal.id}-${i}`} signal={signal} />
                ))}
              </Box>
            )}
          </Paper>

          {/* Per-call table */}
          <Paper variant="outlined" sx={{ p: 1.25 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>Calls</Typography>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" aria-label="Per-call breakdown" data-testid="optimise-call-table">
                <TableHead>
                  <TableRow>
                    <TableCell>#</TableCell>
                    <TableCell>Model</TableCell>
                    <TableCell align="right">In tok</TableCell>
                    <TableCell align="right">Out tok</TableCell>
                    <TableCell align="right">Cost</TableCell>
                    <TableCell align="right">Latency</TableCell>
                    <TableCell align="right">Tools</TableCell>
                    <TableCell>Finish</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(report.calls ?? []).map((call) => (
                    <TableRow key={call.index} hover>
                      <TableCell>{call.index}</TableCell>
                      <TableCell sx={{ fontFamily: monospaceFontFamily, fontSize: '0.72rem' }}>{call.model}</TableCell>
                      <TableCell align="right">{call.inputTokens.toLocaleString()}</TableCell>
                      <TableCell align="right">{call.outputTokens.toLocaleString()}</TableCell>
                      <TableCell align="right">{formatCost(call.estimatedCostUsd)}</TableCell>
                      <TableCell align="right">{formatMs(call.latencyMs)}</TableCell>
                      <TableCell align="right">{call.toolCalls?.length ?? 0}</TableCell>
                      <TableCell>{call.finishReason ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </Paper>
        </>
      )}
    </Box>
  );
}
