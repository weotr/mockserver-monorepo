import { useState, useEffect, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import IconButton from '@mui/material/IconButton';
import DeleteIcon from '@mui/icons-material/Delete';
import VisibilityIcon from '@mui/icons-material/Visibility';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Paper from '@mui/material/Paper';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  listFiles,
  storeFile,
  deleteFile,
  retrieveFileText,
} from '../lib/fileStore';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface FileStoreDialogProps {
  open: boolean;
  onClose: () => void;
  connectionParams: ConnectionParams;
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export default function FileStoreDialog({ open, onClose, connectionParams }: FileStoreDialogProps) {
  const [files, setFiles] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  // Add-file form state
  const [newName, setNewName] = useState('');
  const [newContent, setNewContent] = useState('');
  const [newBase64, setNewBase64] = useState(false);
  const [storing, setStoring] = useState(false);
  const [storeSuccess, setStoreSuccess] = useState<string | null>(null);

  // View state
  const [viewName, setViewName] = useState<string | null>(null);
  const [viewContent, setViewContent] = useState<string | null>(null);
  const [viewError, setViewError] = useState<string | null>(null);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Load file list on open / after mutations. setState only after the await,
  // guarded by `cancelled`, so we never call setState synchronously in the effect.
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    async function load(): Promise<void> {
      try {
        const next = await listFiles(connectionParams);
        if (!cancelled) {
          setFiles(next);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) {
          setFiles([]);
          setError(e instanceof Error ? e.message : String(e));
        }
      }
    }
    void load();
    return () => { cancelled = true; };
  }, [open, connectionParams, refreshTick]);

  const handleStore = useCallback(async () => {
    if (!newName.trim()) return;
    setStoring(true);
    setError(null);
    setStoreSuccess(null);
    try {
      const result = await storeFile(connectionParams, {
        name: newName.trim(),
        content: newContent,
        base64: newBase64 || undefined,
      });
      setStoreSuccess(`Stored "${result.name}" (${result.size} bytes)`);
      setNewName('');
      setNewContent('');
      setNewBase64(false);
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setStoring(false);
    }
  }, [connectionParams, newName, newContent, newBase64, refresh]);

  const handleDelete = useCallback(async (name: string) => {
    setError(null);
    try {
      await deleteFile(connectionParams, name);
      // If the deleted file is currently viewed, clear the view
      if (viewName === name) {
        setViewName(null);
        setViewContent(null);
      }
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [connectionParams, refresh, viewName]);

  const handleView = useCallback(async (name: string) => {
    setViewError(null);
    setViewContent(null);
    setViewName(name);
    try {
      const text = await retrieveFileText(connectionParams, name);
      setViewContent(text);
    } catch (e) {
      setViewError(e instanceof Error ? e.message : String(e));
    }
  }, [connectionParams]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth aria-labelledby="file-store-dialog-title">
      <DialogTitle id="file-store-dialog-title">File Store</DialogTitle>
      <DialogContent dividers>
        {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}
        {storeSuccess && <Alert severity="success" sx={{ mb: 1.5 }}>{storeSuccess}</Alert>}

        {/* File list */}
        {files.length === 0 ? (
          <Box sx={{ textAlign: 'center', py: 2 }}>
            <Typography variant="body2" color="text.secondary">No files in the store</Typography>
          </Box>
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ mb: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }}>Name</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: '0.75rem' }} align="center">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {files.map((name) => (
                  <TableRow key={name}>
                    <TableCell sx={{ fontSize: '0.75rem', fontFamily: 'monospace' }}>{name}</TableCell>
                    <TableCell align="center">
                      <IconButton size="small" onClick={() => void handleView(name)} aria-label={`View ${name}`}>
                        <VisibilityIcon fontSize="small" />
                      </IconButton>
                      <IconButton size="small" onClick={() => void handleDelete(name)} aria-label={`Delete ${name}`}>
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}

        {/* View panel */}
        {viewName && (
          <Box sx={{ mb: 2 }}>
            <Typography variant="caption" color="text.secondary">
              Content of {viewName}
            </Typography>
            {viewError && <Alert severity="error" sx={{ mt: 0.5 }}>{viewError}</Alert>}
            {viewContent !== null && (
              <Box
                component="pre"
                sx={{
                  whiteSpace: 'pre-wrap',
                  fontFamily: 'monospace',
                  fontSize: '0.72rem',
                  m: 0,
                  mt: 0.5,
                  maxHeight: 200,
                  overflow: 'auto',
                  p: 1,
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 1,
                }}
              >
                {viewContent}
              </Box>
            )}
          </Box>
        )}

        {/* Add file form */}
        <Typography variant="subtitle2" sx={{ mt: 1, mb: 1 }}>Add file</Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <TextField
            label="File name"
            size="small"
            fullWidth
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="example.json"
            helperText="Name under which the file will be stored"
          />
          <TextField
            label="Content"
            size="small"
            fullWidth
            multiline
            minRows={4}
            maxRows={12}
            value={newContent}
            onChange={(e) => setNewContent(e.target.value)}
            placeholder="File content here..."
            slotProps={{ input: { sx: { fontFamily: 'monospace', fontSize: '0.78rem' } } }}
          />
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={newBase64}
                onChange={(e) => setNewBase64(e.target.checked)}
              />
            }
            label={
              <Typography variant="body2">
                Base64 encoded (server will decode before storing)
              </Typography>
            }
          />
          <Button
            variant="contained"
            onClick={() => void handleStore()}
            disabled={storing || !newName.trim()}
            sx={{ alignSelf: 'flex-start' }}
          >
            {storing ? 'Storing...' : 'Store File'}
          </Button>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
