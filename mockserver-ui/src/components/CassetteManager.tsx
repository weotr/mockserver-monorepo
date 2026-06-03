import { useState, useMemo, useCallback, useEffect } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import IconButton from '@mui/material/IconButton';
import DeleteIcon from '@mui/icons-material/Delete';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Paper from '@mui/material/Paper';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import {
  listCassettes,
  addCassette,
  removeCassette,
  type CassetteEntry,
} from '../lib/cassetteRegistry';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import {
  listServerCassettes,
  registerServerCassette,
  deleteServerCassette,
} from '../lib/cassetteServer';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface CassetteManagerProps {
  open: boolean;
  onClose: () => void;
  connectionParams: { host: string; port: string; secure: boolean };
}

// ---------------------------------------------------------------------------
// Tab panels
// ---------------------------------------------------------------------------

function ListTab({
  cassettes,
  onRefresh,
  connectionParams,
}: {
  cassettes: CassetteEntry[];
  onRefresh: () => void;
  connectionParams: { host: string; port: string; secure: boolean };
}) {
  const handleRemove = useCallback(
    (path: string) => {
      removeCassette(path);
      // Also remove from the server-side registry (best-effort — ignore failures, e.g. an older
      // server without the cassettes endpoint), then refresh the merged list.
      void deleteServerCassette(connectionParams, path).catch(() => undefined).finally(onRefresh);
    },
    [onRefresh, connectionParams],
  );

  if (cassettes.length === 0) {
    return (
      <Box sx={{ textAlign: 'center', py: 4 }}>
        <Typography variant="body2" color="text.secondary">
          No cassettes tracked yet
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
          Record or load a cassette to see it here.
        </Typography>
      </Box>
    );
  }

  return (
    <TableContainer component={Paper} variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }}>Filename</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }}>Path</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }} align="right">
              Expectations
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }}>Last Used</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }}>Origin</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }} align="center">
              Actions
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {cassettes.map((c) => (
            <TableRow key={c.path}>
              <TableCell sx={{ fontSize: '0.75rem', fontFamily: 'monospace' }}>
                {c.filename}
              </TableCell>
              <TableCell sx={{ fontSize: '0.7rem', fontFamily: 'monospace', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                <Tooltip title={c.path}>
                  <span>{c.path}</span>
                </Tooltip>
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.75rem' }}>
                {c.expectationCount >= 0 ? c.expectationCount : '?'}
              </TableCell>
              <TableCell sx={{ fontSize: '0.7rem' }}>
                {c.lastUsed ? new Date(c.lastUsed).toLocaleString() : 'N/A'}
              </TableCell>
              <TableCell>
                <Chip
                  label={c.origin}
                  size="small"
                  color={c.origin === 'recorded' ? 'primary' : 'secondary'}
                  variant="outlined"
                  sx={{ height: 18, fontSize: '0.6rem' }}
                />
              </TableCell>
              <TableCell align="center">
                <IconButton
                  size="small"
                  onClick={() => handleRemove(c.path)}
                  aria-label={`Remove ${c.filename}`}
                >
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function RecordTab({
  connectionParams,
  onRecorded,
}: {
  connectionParams: { host: string; port: string; secure: boolean };
  onRecorded: () => void;
}) {
  const [path, setPath] = useState('');
  const [requestPath, setRequestPath] = useState('');
  const [host, setHost] = useState('');
  const [recording, setRecording] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const handleRecord = useCallback(async () => {
    if (!path.trim()) return;
    setRecording(true);
    setError(null);
    setSuccess(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const args: Record<string, unknown> = { path: path.trim() };
      if (requestPath.trim()) {
        args['requestPath'] = requestPath.trim();
      }
      if (host.trim()) {
        args['host'] = host.trim();
      }
      const result = await callMcpTool(baseUrl, 'record_llm_fixtures', args);
      if (result.ok && result.result) {
        const count = typeof result.result['count'] === 'number' ? result.result['count'] : -1;
        addCassette(path.trim(), count, 'recorded');
        // Mirror into the server-side registry so the cassette is visible after a reload and from
        // other browsers (best-effort — ignore failures on older servers without the endpoint).
        void registerServerCassette(connectionParams, { path: path.trim(), expectationCount: count, origin: 'recorded' }).catch(() => undefined);
        const msg = typeof result.result['message'] === 'string'
          ? result.result['message']
          : `Recorded ${count} expectation(s)`;
        setSuccess(msg);
        onRecorded();
      } else {
        const errMsg = result.error;
        setError(
          typeof errMsg === 'string'
            ? errMsg
            : typeof errMsg === 'object' && errMsg !== null && 'message' in (errMsg as Record<string, unknown>)
              ? String((errMsg as Record<string, unknown>)['message'])
              : JSON.stringify(errMsg),
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setRecording(false);
    }
  }, [connectionParams, path, requestPath, host, onRecorded]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
      <TextField
        label="File path (required)"
        size="small"
        fullWidth
        value={path}
        onChange={(e) => setPath(e.target.value)}
        placeholder="/path/to/cassette.json"
        helperText="Server filesystem path where the fixture file will be written"
      />
      <TextField
        label="Request path filter (optional)"
        size="small"
        fullWidth
        value={requestPath}
        onChange={(e) => setRequestPath(e.target.value)}
        placeholder="/v1/messages"
        helperText="Only include traffic whose request path matches this value"
      />
      <TextField
        label="Host filter (optional)"
        size="small"
        fullWidth
        value={host}
        onChange={(e) => setHost(e.target.value)}
        placeholder="api.anthropic.com"
        helperText="Only include traffic whose Host header matches this value"
      />
      <Button
        variant="contained"
        onClick={() => void handleRecord()}
        disabled={recording || !path.trim()}
        sx={{ alignSelf: 'flex-start' }}
      >
        {recording ? 'Recording...' : 'Record'}
      </Button>
      {error && <Alert severity="error">{error}</Alert>}
      {success && <Alert severity="success">{success}</Alert>}
    </Box>
  );
}

function LoadTab({
  connectionParams,
  onLoaded,
}: {
  connectionParams: { host: string; port: string; secure: boolean };
  onLoaded: () => void;
}) {
  const [filePath, setFilePath] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const handleLoad = useCallback(async () => {
    if (!filePath.trim()) return;
    setLoading(true);
    setError(null);
    setSuccess(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const result = await callMcpTool(baseUrl, 'load_expectations_from_file', {
        path: filePath.trim(),
      });
      if (result.ok && result.result) {
        const count = typeof result.result['count'] === 'number' ? result.result['count'] : -1;
        addCassette(filePath.trim(), count, 'loaded');
        // Mirror into the server-side registry (best-effort) so it persists across reloads/browsers.
        void registerServerCassette(connectionParams, { path: filePath.trim(), expectationCount: count, origin: 'loaded' }).catch(() => undefined);
        const msg = typeof result.result['message'] === 'string'
          ? result.result['message']
          : `Loaded ${count} expectation(s)`;
        setSuccess(msg);
        onLoaded();
      } else {
        const errMsg = result.error;
        setError(
          typeof errMsg === 'string'
            ? errMsg
            : typeof errMsg === 'object' && errMsg !== null && 'message' in (errMsg as Record<string, unknown>)
              ? String((errMsg as Record<string, unknown>)['message'])
              : JSON.stringify(errMsg),
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, filePath, onLoaded]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
      <TextField
        label="File path (required)"
        size="small"
        fullWidth
        value={filePath}
        onChange={(e) => setFilePath(e.target.value)}
        placeholder="/path/to/cassette.json"
        helperText="Path to a MockServer expectations JSON file on the server filesystem"
      />
      <Button
        variant="contained"
        onClick={() => void handleLoad()}
        disabled={loading || !filePath.trim()}
        sx={{ alignSelf: 'flex-start' }}
      >
        {loading ? 'Loading…' : 'Load Expectations'}
      </Button>
      {error && <Alert severity="error">{error}</Alert>}
      {success && <Alert severity="success">{success}</Alert>}
    </Box>
  );
}

function ExportTab({ cassettes }: { cassettes: CassetteEntry[] }) {
  const [selected, setSelected] = useState('');

  const handleExport = useCallback(() => {
    const entry = cassettes.find((c) => c.path === selected);
    if (!entry) return;

    // Export the cassette metadata as a JSON file for portability
    const exportData = {
      cassette: {
        filename: entry.filename,
        path: entry.path,
        expectationCount: entry.expectationCount,
        lastUsed: entry.lastUsed,
        origin: entry.origin,
      },
      instructions: 'Load this cassette using the Load tab or via the load_expectations_from_file MCP tool.',
    };

    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = entry.filename.replace(/\.json$/, '') + '-cassette-info.json';
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }, [cassettes, selected]);

  if (cassettes.length === 0) {
    return (
      <Box sx={{ textAlign: 'center', py: 4 }}>
        <Typography variant="body2" color="text.secondary">
          No cassettes available to export
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
      <TextField
        label="Select cassette"
        size="small"
        select
        fullWidth
        value={selected}
        onChange={(e) => setSelected(e.target.value)}
        slotProps={{ select: { native: true } }}
      >
        <option value="">-- Select a cassette --</option>
        {cassettes.map((c) => (
          <option key={c.path} value={c.path}>
            {c.filename} ({c.path})
          </option>
        ))}
      </TextField>
      <Button
        variant="contained"
        onClick={handleExport}
        disabled={!selected}
        sx={{ alignSelf: 'flex-start' }}
      >
        Download Cassette Info
      </Button>
      <Typography variant="caption" color="text.secondary">
        Downloads a JSON file with the cassette metadata. The actual fixture file
        lives on the server filesystem and can be copied directly.
      </Typography>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

/**
 * Body component — usable inline (inside the Library tab) without the Dialog chrome. It re-reads
 * cassettes on mount (so the parent re-mounting it — e.g. reopening the dialog or switching back to
 * the Library tab — refreshes the list) and after any record/load/delete mutation.
 */
export function CassetteManagerBody({
  connectionParams,
}: {
  connectionParams: { host: string; port: string; secure: boolean };
}) {
  const [tab, setTab] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [serverCassettes, setServerCassettes] = useState<CassetteEntry[]>([]);

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

  // Fetch the server-side cassette registry whenever the list is (re)opened or mutated. setState
  // only after the await, guarded by `cancelled`, so we never call setState synchronously in the
  // effect. A server without the endpoint simply yields an empty server list (localStorage still works).
  useEffect(() => {
    let cancelled = false;
    async function load(): Promise<void> {
      try {
        const next = await listServerCassettes(connectionParams);
        if (!cancelled) setServerCassettes(next);
      } catch {
        if (!cancelled) setServerCassettes([]);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [connectionParams, refreshKey]);

  // Merge the per-browser localStorage registry with the server-side one, deduped by path. The
  // localStorage entry wins when a cassette exists in both (it carries the freshest local lastUsed).
  // `listCassettes()` reads localStorage on each call; refreshKey is the cache-buster.
  const cassettes = useMemo(
    () => {
      const byPath = new Map<string, CassetteEntry>();
      for (const c of serverCassettes) byPath.set(c.path, c);
      for (const c of listCassettes()) byPath.set(c.path, c);
      return Array.from(byPath.values()).sort((a, b) => {
        const da = a.lastUsed ? new Date(a.lastUsed).getTime() : 0;
        const db = b.lastUsed ? new Date(b.lastUsed).getTime() : 0;
        return db - da;
      });
    },
    [serverCassettes, refreshKey], // eslint-disable-line react-hooks/exhaustive-deps
  );

  const tabLabels = ['List', 'Record', 'Load', 'Export'];

  return (
    <Box>
      <Tabs
        value={tab}
        onChange={(_, v: number) => setTab(v)}
        sx={{ mb: 2, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.8rem' } }}
      >
        {tabLabels.map((label) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>

      {tab === 0 && <ListTab cassettes={cassettes} onRefresh={refresh} connectionParams={connectionParams} />}
      {tab === 1 && <RecordTab connectionParams={connectionParams} onRecorded={refresh} />}
      {tab === 2 && <LoadTab connectionParams={connectionParams} onLoaded={refresh} />}
      {tab === 3 && <ExportTab cassettes={cassettes} />}
    </Box>
  );
}

export default function CassetteManager({ open, onClose, connectionParams }: CassetteManagerProps) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="md"
      fullWidth
      aria-labelledby="cassette-manager-title"
    >
      <DialogTitle id="cassette-manager-title">Cassette Manager</DialogTitle>
      <DialogContent dividers>
        {/* Re-mounted each time the dialog opens, so the body re-fetches on open. */}
        <CassetteManagerBody connectionParams={connectionParams} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
