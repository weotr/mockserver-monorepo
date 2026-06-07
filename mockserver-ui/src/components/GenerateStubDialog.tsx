import { useCallback, useState } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { registerExpectation } from '../lib/generateStub';
import JsonViewer from './JsonViewer';

interface GenerateStubDialogProps {
  open: boolean;
  onClose: () => void;
  suggestions: Record<string, unknown>[];
  confidence: number;
  connectionParams: ConnectionParams;
}

export default function GenerateStubDialog({
  open,
  onClose,
  suggestions,
  confidence,
  connectionParams,
}: GenerateStubDialogProps) {
  const [registering, setRegistering] = useState(false);
  const [registered, setRegistered] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleRegister = useCallback(async () => {
    if (suggestions.length === 0) return;
    setRegistering(true);
    setError(null);
    try {
      await registerExpectation(connectionParams, suggestions[0]!);
      setRegistered(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRegistering(false);
    }
  }, [connectionParams, suggestions]);

  const handleClose = useCallback(() => {
    setRegistered(false);
    setError(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        Generated Expectation
        <Chip
          size="small"
          label={`${Math.round(confidence * 100)}% confidence`}
          color={confidence >= 0.8 ? 'success' : confidence >= 0.5 ? 'warning' : 'error'}
          variant="outlined"
        />
      </DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        {registered && (
          <Alert severity="success" sx={{ mb: 1.5 }}>
            Expectation registered successfully.
          </Alert>
        )}
        {suggestions.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No suggestions returned by the server.
          </Typography>
        ) : (
          <Box>
            <JsonViewer data={suggestions[0]!} collapsed={3} />
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={registering || registered || suggestions.length === 0}
          onClick={() => void handleRegister()}
          startIcon={registering ? <CircularProgress size={16} /> : undefined}
        >
          {registered ? 'Registered' : 'Register Now'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
