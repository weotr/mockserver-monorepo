import { useState, useEffect, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Select from '@mui/material/Select';
import MenuItem from '@mui/material/MenuItem';
import Switch from '@mui/material/Switch';
import FormControlLabel from '@mui/material/FormControlLabel';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { getConfiguration, updateConfiguration, LOG_LEVELS, type Configuration } from '../lib/configuration';

function valueToText(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

export default function ConfigurationDialog({
  open,
  onClose,
  connectionParams,
}: {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}) {
  const [config, setConfig] = useState<Configuration | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    async function load(): Promise<void> {
      try {
        const next = await getConfiguration(connectionParams);
        if (cancelled) return;
        setConfig(next);
        setError(null);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    }
    void load();
    return () => { cancelled = true; };
  }, [open, connectionParams, refreshTick]);

  const apply = useCallback(async (partial: Configuration) => {
    setBusy(true);
    setError(null);
    try {
      await updateConfiguration(connectionParams, partial);
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, refresh]);

  const logLevel = typeof config?.['logLevel'] === 'string' ? (config['logLevel'] as string) : 'INFO';
  const detailed = config?.['detailedMatchFailures'] === true;
  const metrics = config?.['metricsEnabled'] === true;

  const entries = config ? Object.entries(config).filter(([, v]) => v != null && valueToText(v) !== '') : [];

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Server configuration</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Inspect the running server configuration and change common runtime settings. Changes apply
          immediately to this server.
        </Typography>
        {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 1, flexWrap: 'wrap' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="body2">Log level</Typography>
            <Select size="small" value={logLevel} disabled={busy || !config}
              onChange={(e) => void apply({ logLevel: e.target.value })} sx={{ width: 120 }}>
              {LOG_LEVELS.map((l) => <MenuItem key={l} value={l}>{l}</MenuItem>)}
            </Select>
          </Box>
          <FormControlLabel
            control={<Switch size="small" checked={detailed} disabled={busy || !config}
              onChange={(e) => void apply({ detailedMatchFailures: e.target.checked })} />}
            label={<Typography variant="body2">Detailed match failures</Typography>}
          />
          <FormControlLabel
            control={<Switch size="small" checked={metrics} disabled={busy || !config}
              onChange={(e) => void apply({ metricsEnabled: e.target.checked })} />}
            label={<Typography variant="body2">Metrics enabled</Typography>}
          />
        </Box>

        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1, mb: 0.5 }}>
          All settings (read-only)
        </Typography>
        <Box sx={{ maxHeight: 320, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
          <Table size="small" stickyHeader>
            <TableBody>
              {entries.map(([k, v]) => (
                <TableRow key={k}>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', width: '45%', verticalAlign: 'top' }}>{k}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', wordBreak: 'break-all' }}>{valueToText(v)}</TableCell>
                </TableRow>
              ))}
              {entries.length === 0 && (
                <TableRow><TableCell colSpan={2}><Typography variant="body2" color="text.secondary">No configuration loaded.</Typography></TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
