import { useState, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { diffRequests, type DiffResult } from '../lib/diff';
import DiffPanel from './DiffPanel';

const PLACEHOLDER = '{\n  "method": "GET",\n  "path": "/api/users",\n  "headers": { "accept": ["application/json"] }\n}';

/**
 * Compare two captured requests field-by-field via PUT /mockserver/diff and render the result with
 * the shared DiffPanel. Paste each request as JSON (e.g. copied from the Traffic inspector).
 */
export default function DiffRequestsDialog({
  open,
  onClose,
  connectionParams,
}: {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}) {
  const [expected, setExpected] = useState('');
  const [actual, setActual] = useState('');
  const [result, setResult] = useState<DiffResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(async () => {
    let expectedObj: Record<string, unknown>;
    let actualObj: Record<string, unknown>;
    try {
      expectedObj = JSON.parse(expected) as Record<string, unknown>;
    } catch {
      setError('“Expected” request is not valid JSON.');
      return;
    }
    try {
      actualObj = JSON.parse(actual) as Record<string, unknown>;
    } catch {
      setError('“Actual” request is not valid JSON.');
      return;
    }
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await diffRequests(connectionParams, expectedObj, actualObj));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, expected, actual]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth aria-labelledby="diff-requests-title">
      <DialogTitle id="diff-requests-title">Diff two requests</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Paste two requests as JSON to compare them field-by-field. Copy a request from the Traffic
          inspector, or hand-author one.
        </Typography>
        <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
          <TextField
            label="Expected request (JSON)"
            multiline minRows={8} maxRows={20}
            value={expected} onChange={(e) => setExpected(e.target.value)}
            placeholder={PLACEHOLDER}
            sx={{ flex: 1, minWidth: 280 }}
            slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
          />
          <TextField
            label="Actual request (JSON)"
            multiline minRows={8} maxRows={20}
            value={actual} onChange={(e) => setActual(e.target.value)}
            placeholder={PLACEHOLDER}
            sx={{ flex: 1, minWidth: 280 }}
            slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
          />
        </Box>
        {error && <Alert severity="error" sx={{ mt: 1.5 }}>{error}</Alert>}
        {(loading || result) && <DiffPanel result={result} loading={loading} error={null} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={loading || !expected.trim() || !actual.trim()} onClick={() => void submit()}>
          Compare
        </Button>
      </DialogActions>
    </Dialog>
  );
}
