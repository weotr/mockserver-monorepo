import { useState, useCallback, useEffect, useRef, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Paper from '@mui/material/Paper';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import FormControl from '@mui/material/FormControl';
import FormLabel from '@mui/material/FormLabel';
import RadioGroup from '@mui/material/RadioGroup';
import FormControlLabel from '@mui/material/FormControlLabel';
import Radio from '@mui/material/Radio';
import Tooltip from '@mui/material/Tooltip';
import DeleteIcon from '@mui/icons-material/Delete';
import DownloadIcon from '@mui/icons-material/Download';
import RefreshIcon from '@mui/icons-material/Refresh';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import { CassetteManagerBody } from './CassetteManager';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  listWasmModules,
  uploadWasmModule,
  deleteWasmModule,
} from '../lib/wasm';

// ---------------------------------------------------------------------------
// Export sub-tab — download captured content in various formats
// ---------------------------------------------------------------------------

// Two independent axes: what to export (scope) and the file format. They are
// chosen separately — a radio for the scope, a dropdown for the format — rather
// than enumerating every scope×format pair in one long list.

type ExportScope = 'expectations' | 'requests';
type ExportFormat = 'json' | 'java' | 'har' | 'openapi' | 'postman' | 'bruno' | 'logentries' | 'curl';

const SCOPES: { value: ExportScope; label: string; retrieveType: string }[] = [
  { value: 'requests', label: 'Recorded requests', retrieveType: 'REQUEST_RESPONSES' },
  { value: 'expectations', label: 'Active expectations', retrieveType: 'ACTIVE_EXPECTATIONS' },
];

// retrieveFormat is the server's Format enum name; scopes restricts which export
// scopes the server accepts that format for (e.g. JAVA is expectations-only,
// LOG_ENTRIES is requests-only — so the dropdown is filtered by the chosen scope).
const FORMATS: { value: ExportFormat; label: string; retrieveFormat: string; scopes: ExportScope[] }[] = [
  { value: 'json', label: 'MockServer JSON', retrieveFormat: 'JSON', scopes: ['expectations', 'requests'] },
  { value: 'java', label: 'MockServer Java DSL', retrieveFormat: 'JAVA', scopes: ['expectations'] },
  { value: 'har', label: 'HAR (HTTP Archive)', retrieveFormat: 'HAR', scopes: ['expectations', 'requests'] },
  { value: 'openapi', label: 'OpenAPI 3 spec', retrieveFormat: 'OPENAPI', scopes: ['expectations', 'requests'] },
  { value: 'postman', label: 'Postman collection v2.1', retrieveFormat: 'POSTMAN', scopes: ['expectations', 'requests'] },
  { value: 'bruno', label: 'Bruno collection (.zip)', retrieveFormat: 'BRUNO', scopes: ['expectations', 'requests'] },
  { value: 'logentries', label: 'Log entries (JSON)', retrieveFormat: 'LOG_ENTRIES', scopes: ['requests'] },
  { value: 'curl', label: 'cURL commands', retrieveFormat: 'CURL', scopes: ['requests'] },
];

interface ExportDetail {
  description: string;
  filename: string;
}

// Per scope×format description + download filename. Every scope supports every
// format, so the dropdown never needs to change with the selected scope.
const DETAILS: Record<ExportScope, Partial<Record<ExportFormat, ExportDetail>>> = {
  expectations: {
    json: {
      description: 'Round-trippable JSON of every registered expectation. Re-import via PUT /mockserver/expectation.',
      filename: 'mockserver-expectations.json',
    },
    java: {
      description: 'MockServer Java DSL that recreates each expectation — paste into a JUnit test or client.',
      filename: 'mockserver-expectations.java',
    },
    har: {
      description: 'HAR-formatted archive of each expectation as a synthetic request/response pair.',
      filename: 'mockserver-expectations.har',
    },
    openapi: {
      description: 'One operation per (method, path) with the registered response body as an example.',
      filename: 'mockserver-expectations.openapi.json',
    },
    postman: {
      description: 'Postman collection of every expectation as a request item with example response.',
      filename: 'mockserver-expectations.postman.json',
    },
    bruno: {
      description: 'Bruno collection — one .bru file per expectation, packaged as a zip archive.',
      filename: 'mockserver-expectations.bruno.zip',
    },
  },
  requests: {
    json: {
      description: 'Every request/response pair MockServer has logged, as a JSON array.',
      filename: 'mockserver-traffic.json',
    },
    har: {
      description: 'Browser-tooling-compatible archive of every captured request/response pair.',
      filename: 'mockserver-traffic.har',
    },
    openapi: {
      description: 'OpenAPI spec derived from observed traffic; useful for capturing a real API shape.',
      filename: 'mockserver-traffic.openapi.json',
    },
    postman: {
      description: 'Postman collection where each item is a captured request with its observed response.',
      filename: 'mockserver-traffic.postman.json',
    },
    bruno: {
      description: 'Bruno collection (one .bru per captured request) packaged as a zip archive.',
      filename: 'mockserver-traffic.bruno.zip',
    },
    logentries: {
      description: 'Raw MockServer log events for the captured request/response pairs (verbose JSON; mainly for debugging).',
      filename: 'mockserver-traffic.log-entries.json',
    },
    curl: {
      description: 'A cURL command per captured request — paste into a shell to replay the traffic.',
      filename: 'mockserver-traffic.curl.sh',
    },
  },
};

function ExportTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [scope, setScope] = useState<ExportScope>('requests');
  const [format, setFormat] = useState<ExportFormat>('har');
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const scopeMeta = SCOPES.find((s) => s.value === scope)!;
  const availableFormats = FORMATS.filter((f) => f.scopes.includes(scope));
  const formatMeta = FORMATS.find((f) => f.value === format)!;
  const detail = DETAILS[scope][format] ?? DETAILS[scope].json!;

  // The export caveat depends on the scope: exporting the expectation graph to a
  // request-collection format is lossy (dynamic behaviour becomes placeholders),
  // whereas exporting observed traffic is just a snapshot of what was handled.
  let notice: string | null = null;
  if (scope === 'expectations' && ['openapi', 'postman', 'bruno', 'har'].includes(format)) {
    notice =
      'Best-effort export. Positive-string matchers round-trip cleanly, but NottableString ' +
      'negation, regex bodies, and dynamic actions (forward / template / callback / error / LLM) ' +
      'become placeholders — these formats describe client requests and example responses, not ' +
      'the full MockServer expectation graph.';
  } else if (scope === 'requests' && ['openapi', 'postman', 'bruno'].includes(format)) {
    notice =
      'Derived from the traffic MockServer has observed — only the requests it actually handled ' +
      'are included.';
  }

  const handleScopeChange = (next: ExportScope) => {
    setScope(next);
    // The chosen format may not be valid for the new scope (e.g. JAVA only
    // applies to expectations) — fall back to JSON, which every scope supports.
    if (!FORMATS.some((f) => f.value === format && f.scopes.includes(next))) {
      setFormat('json');
    }
  };

  const handleDownload = useCallback(async () => {
    setDownloading(true);
    setError(null);
    try {
      const protocol = connectionParams.secure ? 'https' : 'http';
      const base = `${protocol}://${connectionParams.host}:${connectionParams.port}`;
      const path = `/mockserver/retrieve?type=${scopeMeta.retrieveType}&format=${formatMeta.retrieveFormat}`;
      const res = await fetch(`${base}${path}`, { method: 'PUT' });
      if (!res.ok) throw new Error(`MockServer returned ${res.status}: ${res.statusText}`);
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = detail.filename;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(objectUrl);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setDownloading(false);
    }
  }, [scopeMeta, formatMeta, detail, connectionParams]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
      <Typography variant="body2" color="text.secondary">
        Download captured traffic or registered expectations as a file. The file is
        produced by the running MockServer instance — what you see in the dashboard
        is what you get.
      </Typography>
      <FormControl>
        <FormLabel sx={{ fontSize: '0.8rem' }}>What to export</FormLabel>
        <RadioGroup
          row
          value={scope}
          onChange={(e) => handleScopeChange(e.target.value as ExportScope)}
        >
          {SCOPES.map((s) => (
            <FormControlLabel
              key={s.value}
              value={s.value}
              control={<Radio size="small" />}
              label={s.label}
            />
          ))}
        </RadioGroup>
      </FormControl>
      <TextField
        label="Format"
        size="small"
        select
        value={format}
        onChange={(e) => setFormat(e.target.value as ExportFormat)}
        helperText={detail.description}
        sx={{ maxWidth: 360 }}
      >
        {availableFormats.map((f) => (
          <MenuItem key={f.value} value={f.value}>
            {f.label}
          </MenuItem>
        ))}
      </TextField>
      <Box>
        <Button
          variant="contained"
          size="small"
          startIcon={<DownloadIcon sx={{ fontSize: '0.875rem' }} />}
          onClick={() => void handleDownload()}
          disabled={downloading}
        >
          {downloading ? 'Downloading…' : `Download ${detail.filename}`}
        </Button>
      </Box>
      {error && <Alert severity="error" variant="outlined">{error}</Alert>}
      {notice && (
        <Alert severity="info" variant="outlined" sx={{ fontSize: '0.8rem' }}>
          {notice}
        </Alert>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// WASM Modules tab
// ---------------------------------------------------------------------------

const WASM_POLL_INTERVAL_MS = 8000;

function WasmModulesTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [modules, setModules] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploadName, setUploadName] = useState('');
  const [refreshTick, setRefreshTick] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Poll modules list
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await listWasmModules(connectionParams, controller.signal);
        if (cancelled) return;
        setModules(result);
        setError(null);
        setLoading(false);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      } finally {
        if (!cancelled) timer = setTimeout(() => void poll(), WASM_POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick]);

  const handleUpload = useCallback(async () => {
    if (!uploadName.trim()) {
      setActionError('Module name is required');
      return;
    }
    const input = fileInputRef.current;
    if (!input?.files?.length) {
      setActionError('Select a .wasm file to upload');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const file = input.files[0]!;
      const bytes = await file.arrayBuffer();
      await uploadWasmModule(connectionParams, uploadName.trim(), bytes);
      setUploadName('');
      if (input) input.value = '';
      setRefreshTick((t) => t + 1);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, uploadName]);

  const handleDelete = useCallback(async (name: string) => {
    setBusy(true);
    setActionError(null);
    try {
      await deleteWasmModule(connectionParams, name);
      setRefreshTick((t) => t + 1);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
      <Typography variant="body2" color="text.secondary">
        Upload and manage WASM custom rule modules. Each module can be referenced
        by name in expectation actions.
      </Typography>

      {/* Upload form */}
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField
          size="small"
          label="Module name"
          placeholder="my-rule"
          value={uploadName}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setUploadName(e.target.value)}
          sx={{ minWidth: 180 }}
        />
        <Button
          size="small"
          variant="outlined"
          component="label"
          startIcon={<UploadFileIcon sx={{ fontSize: '0.875rem' }} />}
          sx={{ height: 40 }}
        >
          Select .wasm
          <input
            ref={fileInputRef}
            type="file"
            accept=".wasm"
            hidden
          />
        </Button>
        <Button
          variant="contained"
          size="small"
          disabled={busy}
          onClick={() => void handleUpload()}
          sx={{ height: 40 }}
        >
          Upload
        </Button>
        <Tooltip title="Refresh module list">
          <IconButton size="small" onClick={() => setRefreshTick((t) => t + 1)} aria-label="Refresh WASM modules">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {actionError && (
        <Alert severity="warning" onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}

      {error && (
        <Alert severity="error" variant="outlined">{error}</Alert>
      )}

      {/* Modules table */}
      {loading ? (
        <Typography variant="body2" color="text.secondary">Loading...</Typography>
      ) : modules.length === 0 ? (
        <Typography variant="body2" color="text.secondary">No WASM modules loaded.</Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Module Name</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {modules.map((name) => (
                <TableRow key={name}>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>{name}</Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="Delete module">
                      <span>
                        <IconButton
                          size="small"
                          aria-label={`Delete WASM module ${name}`}
                          disabled={busy}
                          onClick={() => void handleDelete(name)}
                        >
                          <DeleteIcon fontSize="small" />
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
  );
}

// ---------------------------------------------------------------------------
// Main view — tab strip across Export / Cassettes / WASM Modules
// ---------------------------------------------------------------------------

export interface LibraryViewProps {
  connectionParams: ConnectionParams;
}

const TABS = ['Export', 'Cassettes', 'WASM Modules'];

export default function LibraryView({ connectionParams }: LibraryViewProps) {
  const [tab, setTab] = useState(0);

  return (
    <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', p: 1 }}>
      <Paper variant="outlined" sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <Tabs
          value={tab}
          onChange={(_, v: number) => setTab(v)}
          sx={{ borderBottom: 1, borderColor: 'divider', minHeight: 36, '& .MuiTab-root': { minHeight: 36, py: 0.5, fontSize: '0.8rem' } }}
        >
          {TABS.map((label) => (
            <Tab key={label} label={label} />
          ))}
        </Tabs>
        <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }}>
          {tab === 0 && <ExportTab connectionParams={connectionParams} />}
          {tab === 1 && <CassetteManagerBody connectionParams={connectionParams} />}
          {tab === 2 && <WasmModulesTab connectionParams={connectionParams} />}
        </Box>
      </Paper>
    </Box>
  );
}
