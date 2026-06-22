import { useCallback, useMemo, useState, type ChangeEvent } from 'react';
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
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import RefreshIcon from '@mui/icons-material/Refresh';
import ConfirmDialog from './ConfirmDialog';
import { useAutoRefresh } from '../hooks/useAutoRefresh';
import { humanizeError } from '../lib/errorMessage';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchDriftRecords,
  clearDrift,
  type DriftRecord,
  type DriftResponse,
} from '../lib/drift';

interface DriftPanelProps {
  connectionParams: ConnectionParams;
}

const POLL_INTERVAL_MS = 5000;

/**
 * The drift endpoint returns no JSON error envelope on 404, so a missing
 * endpoint surfaces as the status-line message ("HTTP 404 Not Found") or the
 * humanized "isn’t available" copy. Detect both so the panel can show the
 * "not available on an older server" branch rather than a generic error.
 */
function isUnavailable(message: string): boolean {
  return (
    message.includes('404') ||
    message.includes('Not Found') ||
    message.includes('isn’t available')
  );
}

type DriftType =
  | 'STATUS'
  | 'SCHEMA_FIELD_REMOVED'
  | 'SCHEMA_FIELD_ADDED'
  | 'SCHEMA_TYPE_CHANGED'
  | 'PERFORMANCE'
  | 'HEADER_ADDED'
  | 'HEADER_REMOVED'
  | 'HEADER_CHANGED';

function driftTypeColor(driftType: string): 'warning' | 'error' | 'info' | 'default' | 'secondary' | 'primary' {
  switch (driftType as DriftType) {
    case 'STATUS':
      return 'warning';
    case 'SCHEMA_FIELD_REMOVED':
      return 'error';
    case 'SCHEMA_FIELD_ADDED':
      return 'info';
    case 'SCHEMA_TYPE_CHANGED':
      return 'warning';
    case 'PERFORMANCE':
      return 'secondary';
    case 'HEADER_ADDED':
    case 'HEADER_REMOVED':
    case 'HEADER_CHANGED':
      return 'default';
    default:
      return 'default';
  }
}

function severityColor(severity: string | undefined): 'error' | 'warning' | 'info' | 'default' {
  switch (severity) {
    case 'BREAKING':
      return 'error';
    case 'WARNING':
      return 'warning';
    case 'INFORMATIONAL':
      return 'info';
    default:
      return 'default';
  }
}

function formatTimestamp(epochMs: number): string {
  try {
    return new Date(epochMs).toLocaleTimeString();
  } catch {
    return String(epochMs);
  }
}

export default function DriftPanel({ connectionParams }: DriftPanelProps) {
  const [data, setData] = useState<DriftResponse>({ count: 0, drifts: [] });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [filterText, setFilterText] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);

  // Auto-refresh the read-only drift feed. The lib throws on a non-OK response,
  // so a 500 surfaces as a real error and a 404 routes to the "not available"
  // branch — neither is silently swallowed as "no drift" any more.
  const loadDrift = useCallback(async (signal?: AbortSignal) => {
    try {
      const response = await fetchDriftRecords(connectionParams, undefined, 50, signal);
      setData(response);
      setLoadError(null);
    } catch (e) {
      if (signal?.aborted) return;
      setLoadError(humanizeError(e).message);
    }
  }, [connectionParams]);

  useAutoRefresh(loadDrift, { intervalMs: POLL_INTERVAL_MS });

  // Manual force-refresh (the existing Refresh button) — same fetch, off-cycle.
  const refresh = useCallback(() => {
    void loadDrift();
  }, [loadDrift]);

  const filteredDrifts = useMemo(() => {
    if (!filterText.trim()) return data.drifts;
    const lower = filterText.toLowerCase();
    return data.drifts.filter((d) => d.expectationId.toLowerCase().includes(lower));
  }, [data.drifts, filterText]);

  const handleClear = useCallback(() => {
    setBusy(true);
    setActionError(null);
    clearDrift(connectionParams)
      .then(() => refresh())
      .catch((e) => setActionError(humanizeError(e).message))
      .finally(() => setBusy(false));
  }, [connectionParams, refresh]);

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Drift Detection
        </Typography>
        <Chip
          size="small"
          label={`${data.count} detected`}
          color={data.count > 0 ? 'warning' : 'default'}
          variant="outlined"
        />
        <Box sx={{ flex: 1 }} />
        <TextField
          size="small"
          label="Filter by expectation"
          placeholder="expectation ID..."
          value={filterText}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setFilterText(e.target.value)}
          sx={{ width: 220 }}
        />
        <Tooltip title="Clear all drift records">
          <span>
            <Button
              size="small"
              color="error"
              startIcon={<DeleteSweepIcon fontSize="small" />}
              disabled={busy || data.count === 0}
              onClick={() => setConfirmOpen(true)}
            >
              Clear
            </Button>
          </span>
        </Tooltip>
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh drift">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        MockServer compares proxied responses against stubs in proxy mode and reports field-level drifts.
      </Typography>

      {loadError && (
        <Alert
          severity={isUnavailable(loadError) ? 'info' : 'error'}
          sx={{ mb: 1.5 }}
          action={
            <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
          }
        >
          <AlertTitle>
            {isUnavailable(loadError)
              ? 'Drift detection not available'
              : 'Could not load drift records'}
          </AlertTitle>
          {isUnavailable(loadError)
            ? 'The connected server does not support drift detection. This feature requires a newer version of MockServer.'
            : loadError}
        </Alert>
      )}

      {actionError && (
        <Alert severity="warning" sx={{ mb: 1.5 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 1.25 }}>
        {filteredDrifts.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            No drift detected. MockServer compares proxied responses against stubs in proxy mode.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Expectation</TableCell>
                  <TableCell>Drift Type</TableCell>
                  <TableCell>Field</TableCell>
                  <TableCell>Expected</TableCell>
                  <TableCell>Actual</TableCell>
                  <TableCell align="right">Confidence</TableCell>
                  <TableCell>Severity</TableCell>
                  <TableCell>Time</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredDrifts.map((drift: DriftRecord, i: number) => (
                  <TableRow key={`${drift.expectationId}-${drift.field}-${i}`}>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {drift.expectationId}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={drift.driftType}
                        color={driftTypeColor(drift.driftType)}
                        variant="outlined"
                        sx={{ height: 20, fontSize: '0.65rem' }}
                      />
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {drift.field}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Tooltip title={drift.expectedValue ?? '-'}>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', display: 'block', whiteSpace: 'nowrap' }}>
                          {drift.expectedValue ?? '-'}
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      <Tooltip title={drift.actualValue ?? '-'}>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', display: 'block', whiteSpace: 'nowrap' }}>
                          {drift.actualValue ?? '-'}
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell align="right">
                      <Typography variant="caption">
                        {Math.round(drift.confidence * 100)}%
                      </Typography>
                    </TableCell>
                    <TableCell>
                      {drift.semanticSeverity && (
                        <Tooltip title={drift.semanticExplanation ?? ''}>
                          <Chip
                            size="small"
                            label={drift.semanticSeverity}
                            color={severityColor(drift.semanticSeverity)}
                            sx={{ height: 20, fontSize: '0.65rem' }}
                          />
                        </Tooltip>
                      )}
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {formatTimestamp(drift.epochTimeMs)}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      <ConfirmDialog
        open={confirmOpen}
        title="Clear all drift records?"
        message={`This removes all ${data.count} detected drift record${data.count === 1 ? '' : 's'}. This cannot be undone.`}
        confirmLabel="Clear drift records"
        onConfirm={handleClear}
        onClose={() => setConfirmOpen(false)}
      />
    </Box>
  );
}
