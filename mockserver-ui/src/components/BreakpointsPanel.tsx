import { useCallback, useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import TextField from '@mui/material/TextField';
import RefreshIcon from '@mui/icons-material/Refresh';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchBreakpoints,
  continueBreakpoint,
  modifyBreakpoint,
  abortBreakpoint,
  type PausedExchange,
  type BreakpointListResponse,
} from '../lib/breakpoints';

interface BreakpointsPanelProps {
  connectionParams: ConnectionParams;
}

const POLL_INTERVAL_MS = 2000;

function formatAge(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const secs = Math.round(ms / 1000);
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  const remainSecs = secs % 60;
  return `${mins}m ${remainSecs}s`;
}

export default function BreakpointsPanel({ connectionParams }: BreakpointsPanelProps) {
  const [data, setData] = useState<BreakpointListResponse>({ pausedExchanges: [], count: 0 });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const [busy, setBusy] = useState(false);

  // Modify dialog state
  const [modifyTarget, setModifyTarget] = useState<PausedExchange | null>(null);
  const [modifyJson, setModifyJson] = useState('');
  const [modifyError, setModifyError] = useState<string | null>(null);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Poll breakpoints list.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const response = await fetchBreakpoints(connectionParams, controller.signal);
        if (cancelled) return;
        setData(response);
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

  const handleContinue = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await continueBreakpoint(connectionParams, id);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const handleAbort = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      try {
        await abortBreakpoint(connectionParams, id);
        refresh();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [connectionParams, refresh],
  );

  const openModifyDialog = useCallback((exchange: PausedExchange) => {
    setModifyTarget(exchange);
    setModifyJson(JSON.stringify(exchange.request ?? {}, null, 2));
    setModifyError(null);
  }, []);

  const handleModifySubmit = useCallback(async () => {
    if (!modifyTarget) return;
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(modifyJson) as Record<string, unknown>;
    } catch {
      setModifyError('Invalid JSON');
      return;
    }
    setBusy(true);
    setModifyError(null);
    try {
      await modifyBreakpoint(connectionParams, modifyTarget.id, parsed);
      setModifyTarget(null);
      refresh();
    } catch (e) {
      setModifyError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, modifyTarget, modifyJson, refresh]);

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Breakpoints
        </Typography>
        <Chip
          size="small"
          label={`${data.count} paused`}
          color={data.count > 0 ? 'warning' : 'default'}
          variant="outlined"
        />
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh breakpoints">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Requests paused by breakpoint expectations. Continue, modify, or abort each exchange.
      </Typography>

      {loadError && (
        <Alert
          severity="error"
          sx={{ mb: 1.5 }}
          action={
            <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry">
              <RefreshIcon fontSize="small" />
            </IconButton>
          }
        >
          <AlertTitle>Could not load paused exchanges</AlertTitle>
          {loadError}
        </Alert>
      )}

      {actionError && (
        <Alert severity="warning" sx={{ mb: 1.5 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 1.25 }}>
        {data.pausedExchanges.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            No paused requests. Breakpoint expectations pause matching requests so you can inspect and modify them before forwarding.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Method</TableCell>
                  <TableCell>Path</TableCell>
                  <TableCell>Age</TableCell>
                  <TableCell>ID</TableCell>
                  <TableCell>Expectation</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.pausedExchanges.map((exchange) => (
                  <TableRow key={exchange.id}>
                    <TableCell>
                      <Chip
                        size="small"
                        label={exchange.request?.method ?? '?'}
                        color="primary"
                        variant="outlined"
                        sx={{ height: 20, fontSize: '0.65rem' }}
                      />
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {exchange.request?.path ?? '/'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption">
                        {formatAge(exchange.ageMillis)}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace', maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' }}>
                        {exchange.id}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {exchange.expectationId ?? '-'}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      <Box sx={{ display: 'flex', gap: 0.5, justifyContent: 'flex-end' }}>
                        <Tooltip title="Continue (forward unchanged)">
                          <span>
                            <IconButton
                              size="small"
                              color="success"
                              disabled={busy}
                              onClick={() => void handleContinue(exchange.id)}
                              aria-label={`Continue ${exchange.id}`}
                            >
                              <PlayArrowIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Tooltip title="Modify request before forwarding">
                          <span>
                            <IconButton
                              size="small"
                              color="info"
                              disabled={busy}
                              onClick={() => openModifyDialog(exchange)}
                              aria-label={`Modify ${exchange.id}`}
                            >
                              <EditIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Tooltip title="Abort (do not forward)">
                          <span>
                            <IconButton
                              size="small"
                              color="error"
                              disabled={busy}
                              onClick={() => void handleAbort(exchange.id)}
                              aria-label={`Abort ${exchange.id}`}
                            >
                              <BlockIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                      </Box>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {/* Modify dialog */}
      <Dialog open={modifyTarget !== null} onClose={() => setModifyTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Modify Request</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Edit the request JSON, then send the modified request.
          </Typography>
          {modifyError && (
            <Alert severity="error" sx={{ mb: 1 }}>
              {modifyError}
            </Alert>
          )}
          <TextField
            multiline
            minRows={6}
            maxRows={20}
            fullWidth
            value={modifyJson}
            onChange={(e) => setModifyJson(e.target.value)}
            slotProps={{
              input: {
                sx: { fontFamily: 'monospace', fontSize: '0.8rem' },
              },
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setModifyTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={busy} onClick={() => void handleModifySubmit()}>
            Send Modified
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
