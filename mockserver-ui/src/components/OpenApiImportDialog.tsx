import { useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import { importOpenApi } from '../lib/openapiImport';
import type { ConnectionParams } from '../hooks/useConnectionParams';

interface OpenApiImportDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

/**
 * Dialog to import an OpenAPI v3 spec (URL or inline JSON/YAML) via
 * PUT /mockserver/openapi, generating one expectation per operation. Import is
 * incremental — re-importing the same spec updates in place and prunes removed
 * operations.
 */
export default function OpenApiImportDialog({ open, onClose, connectionParams }: OpenApiImportDialogProps) {
  const [spec, setSpec] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [createdCount, setCreatedCount] = useState<number | null>(null);

  const handleImport = async () => {
    setBusy(true);
    setError(null);
    setCreatedCount(null);
    try {
      const created = await importOpenApi(connectionParams, spec.trim());
      setCreatedCount(created.length);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const handleClose = () => {
    setSpec('');
    setError(null);
    setCreatedCount(null);
    onClose();
  };

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle>Import OpenAPI</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Paste an OpenAPI v3 spec (JSON or YAML) or a spec URL. MockServer generates one
          expectation per operation. Re-importing the same spec updates the expectations in place
          and prunes operations that have been removed.
        </Typography>
        <TextField
          value={spec}
          onChange={(e) => setSpec(e.target.value)}
          label="OpenAPI spec or URL"
          multiline
          minRows={10}
          fullWidth
          spellCheck={false}
        />
        {error !== null && (
          <Alert severity="error" sx={{ mt: 1 }}>
            {error}
          </Alert>
        )}
        {createdCount !== null && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Imported {createdCount} expectation{createdCount === 1 ? '' : 's'}.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Close</Button>
        <Button
          variant="contained"
          onClick={() => void handleImport()}
          disabled={busy || spec.trim().length === 0}
        >
          {busy ? 'Importing…' : 'Import'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
