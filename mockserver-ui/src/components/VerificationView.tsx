import { useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Select from '@mui/material/Select';
import MenuItem from '@mui/material/MenuItem';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Alert from '@mui/material/Alert';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { parseKeyValueLines } from '../lib/standardCodegen';
import {
  verifyRequest,
  verifySequence,
  type VerificationTimesMode,
  type VerifyResult,
} from '../lib/verification';

const METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'];

interface RequestForm {
  method: string;
  path: string;
  headers: string;
  queryString: string;
  body: string;
}

function emptyRequest(): RequestForm {
  return { method: '', path: '', headers: '', queryString: '', body: '' };
}

/** Build an httpRequest matcher object from a form row (omitting empty fields). */
function buildHttpRequest(form: RequestForm): Record<string, unknown> {
  const req: Record<string, unknown> = {};
  if (form.method.trim()) req['method'] = form.method.trim();
  if (form.path.trim()) req['path'] = form.path.trim();
  const headers = parseKeyValueLines(form.headers, ':');
  if (headers) req['headers'] = headers;
  const query = parseKeyValueLines(form.queryString, '=');
  if (query) req['queryStringParameters'] = query;
  if (form.body.trim()) req['body'] = form.body;
  return req;
}

function RequestFields({ form, onChange }: { form: RequestForm; onChange: (f: RequestForm) => void }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <Select
          size="small"
          displayEmpty
          value={form.method}
          onChange={(e) => onChange({ ...form, method: e.target.value })}
          sx={{ width: 130 }}
          renderValue={(v) => (v ? String(v) : 'Any method')}
        >
          {METHODS.map((m) => <MenuItem key={m || 'any'} value={m}>{m || 'Any method'}</MenuItem>)}
        </Select>
        <TextField size="small" label="Path" placeholder="/api/orders" value={form.path}
          onChange={(e) => onChange({ ...form, path: e.target.value })} sx={{ flex: 1 }} />
      </Box>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField size="small" label="Headers (Name: value per line)" multiline minRows={1} maxRows={4}
          value={form.headers} onChange={(e) => onChange({ ...form, headers: e.target.value })} sx={{ flex: 1 }} />
        <TextField size="small" label="Query (key=value per line)" multiline minRows={1} maxRows={4}
          value={form.queryString} onChange={(e) => onChange({ ...form, queryString: e.target.value })} sx={{ flex: 1 }} />
      </Box>
      <TextField size="small" label="Body (substring/JSON match)" multiline minRows={1} maxRows={6}
        value={form.body} onChange={(e) => onChange({ ...form, body: e.target.value })}
        slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }} />
    </Box>
  );
}

function ResultAlert({ result }: { result: VerifyResult | null }) {
  if (!result) return null;
  if (result.verified) {
    return (
      <Alert severity="success" icon={<CheckCircleIcon fontSize="inherit" />} sx={{ mt: 1.5 }}>
        Verified — the server received requests matching this assertion.
      </Alert>
    );
  }
  return (
    <Alert severity="error" sx={{ mt: 1.5 }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>Verification failed</Typography>
      <Box component="pre" sx={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: '0.75rem', m: 0, mt: 0.5 }}>
        {result.failureMessage}
      </Box>
    </Alert>
  );
}

export default function VerificationView({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [mode, setMode] = useState<'single' | 'sequence'>('single');

  // Single-request verification
  const [single, setSingle] = useState<RequestForm>(emptyRequest);
  const [timesMode, setTimesMode] = useState<VerificationTimesMode>('atLeast');
  const [count, setCount] = useState(1);
  const [atMost, setAtMost] = useState(1);

  // Sequence verification
  const [sequence, setSequence] = useState<RequestForm[]>([emptyRequest(), emptyRequest()]);

  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<VerifyResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async (fn: () => Promise<VerifyResult>) => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await fn());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const verifySingle = () => run(() =>
    verifyRequest(connectionParams, buildHttpRequest(single), { mode: timesMode, count, atMost }));

  const verifySeq = () => run(() =>
    verifySequence(connectionParams, sequence.map(buildHttpRequest)));

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Verification</Typography>
        <ToggleButtonGroup
          size="small" exclusive value={mode}
          onChange={(_, v: 'single' | 'sequence' | null) => { if (v) { setMode(v); setResult(null); } }}
          sx={{ ml: 1 }}
        >
          <ToggleButton value="single">Single request</ToggleButton>
          <ToggleButton value="sequence">Ordered sequence</ToggleButton>
        </ToggleButtonGroup>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Assert against the requests MockServer has already received — pass means it happened, fail
        shows the closest matches and the actual count.
      </Typography>

      {mode === 'single' ? (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <RequestFields form={single} onChange={setSingle} />
          <Box sx={{ display: 'flex', gap: 1, mt: 1.5, alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="body2" color="text.secondary">Received</Typography>
            <Select size="small" value={timesMode} onChange={(e) => setTimesMode(e.target.value as VerificationTimesMode)} sx={{ width: 150 }}>
              <MenuItem value="atLeast">at least</MenuItem>
              <MenuItem value="atMost">at most</MenuItem>
              <MenuItem value="exactly">exactly</MenuItem>
              <MenuItem value="between">between</MenuItem>
            </Select>
            <TextField size="small" type="number" label={timesMode === 'between' ? 'min' : 'times'} value={count}
              onChange={(e) => setCount(Math.max(0, Number(e.target.value) || 0))} sx={{ width: 90 }} />
            {timesMode === 'between' && (
              <>
                <Typography variant="body2" color="text.secondary">and</Typography>
                <TextField size="small" type="number" label="max" value={atMost}
                  onChange={(e) => setAtMost(Math.max(0, Number(e.target.value) || 0))} sx={{ width: 90 }} />
              </>
            )}
            <Typography variant="body2" color="text.secondary">time(s)</Typography>
            <Button variant="contained" size="small" disabled={busy} onClick={verifySingle} sx={{ ml: 'auto' }}>
              Verify
            </Button>
          </Box>
        </Paper>
      ) : (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            The requests must have been received in this order (other requests may occur in between).
          </Typography>
          {sequence.map((row, i) => (
            <Box key={i} sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', mb: 1.5 }}>
              <Typography variant="caption" sx={{ mt: 1, width: 20 }}>{i + 1}.</Typography>
              <Box sx={{ flex: 1 }}><RequestFields form={row} onChange={(f) => setSequence(sequence.map((r, j) => j === i ? f : r))} /></Box>
              <IconButton size="small" aria-label="Remove step" disabled={sequence.length <= 1}
                onClick={() => setSequence(sequence.filter((_, j) => j !== i))}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
          ))}
          <Box sx={{ display: 'flex', gap: 1, mt: 1 }}>
            <Button size="small" startIcon={<AddIcon />} onClick={() => setSequence([...sequence, emptyRequest()])}>
              Add step
            </Button>
            <Button variant="contained" size="small" disabled={busy} onClick={verifySeq} sx={{ ml: 'auto' }}>
              Verify sequence
            </Button>
          </Box>
        </Paper>
      )}

      {error && <Alert severity="error" sx={{ mt: 1.5 }}>{error}</Alert>}
      <ResultAlert result={result} />
    </Box>
  );
}
