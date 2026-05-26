import { useState, useMemo, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import FormControlLabel from '@mui/material/FormControlLabel';
import Switch from '@mui/material/Switch';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import Snackbar from '@mui/material/Snackbar';
import IconButton from '@mui/material/IconButton';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import Typography from '@mui/material/Typography';
import type { ParsedTraffic } from '../lib/llmTraffic';
import {
  PROVIDERS,
  extractExpectationFromCapture,
  type ExpectationDraft,
  type ProviderName,
  type ToolCallDraft,
} from '../lib/expectationFromCapture';
import {
  expectationToJson,
  expectationToJava,
  expectationToMcpArgs,
} from '../lib/llmExpectationCodegen';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import CopyButton from './CopyButton';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface CaptureAsMockDialogProps {
  open: boolean;
  onClose: () => void;
  parsed: ParsedTraffic;
  path: string;
  connectionParams: { host: string; port: string; secure: boolean };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function CaptureAsMockDialog({
  open,
  onClose,
  parsed,
  path,
  connectionParams,
}: CaptureAsMockDialogProps) {
  // Extract default draft from the capture
  const defaultDraft = useMemo(
    () => extractExpectationFromCapture(parsed, path),
    [parsed, path],
  );

  // Editable state
  const [draft, setDraft] = useState<ExpectationDraft>(defaultDraft);
  const [tab, setTab] = useState(0);
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [snackOpen, setSnackOpen] = useState(false);

  // Reset state when dialog opens with new data
  const [prevPath, setPrevPath] = useState(path);
  if (path !== prevPath) {
    setPrevPath(path);
    setDraft(defaultDraft);
    setTab(0);
    setError(null);
  }

  const updateDraft = useCallback(
    (partial: Partial<ExpectationDraft>) => setDraft((d) => ({ ...d, ...partial })),
    [],
  );

  const updateToolCall = useCallback(
    (index: number, partial: Partial<ToolCallDraft>) =>
      setDraft((d) => ({
        ...d,
        toolCalls: d.toolCalls.map((tc, i) => (i === index ? { ...tc, ...partial } : tc)),
      })),
    [],
  );

  const addToolCall = useCallback(
    () =>
      setDraft((d) => ({
        ...d,
        toolCalls: [...d.toolCalls, { name: '', arguments: '' }],
      })),
    [],
  );

  const removeToolCall = useCallback(
    (index: number) =>
      setDraft((d) => ({
        ...d,
        toolCalls: d.toolCalls.filter((_, i) => i !== index),
      })),
    [],
  );

  // Codegen outputs
  const jsonOutput = useMemo(() => expectationToJson(draft), [draft]);
  const javaOutput = useMemo(() => expectationToJava(draft), [draft]);

  // Register handler
  const handleRegister = useCallback(async () => {
    setRegistering(true);
    setError(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const args = expectationToMcpArgs(draft);
      const result = await callMcpTool(baseUrl, 'mock_llm_completion', args);
      if (result.ok) {
        setSnackOpen(true);
        onClose();
      } else {
        setError(
          typeof result.error === 'string'
            ? result.error
            : JSON.stringify(result.error, null, 2),
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setRegistering(false);
    }
  }, [connectionParams, draft, onClose]);

  const tabLabels = ['Edit', 'Copy as JSON', 'Copy as Java'];

  return (
    <>
      <Dialog
        open={open}
        onClose={onClose}
        maxWidth="md"
        fullWidth
        aria-labelledby="capture-as-mock-title"
      >
        <DialogTitle id="capture-as-mock-title">Capture as Mock</DialogTitle>
        <DialogContent dividers>
          <Tabs
            value={tab}
            onChange={(_, v: number) => setTab(v)}
            sx={{ mb: 2, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.8rem' } }}
          >
            {tabLabels.map((label) => (
              <Tab key={label} label={label} />
            ))}
          </Tabs>

          {/* Tab 0: Editable fields */}
          {tab === 0 && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <TextField
                label="Path"
                size="small"
                fullWidth
                value={draft.path}
                onChange={(e) => updateDraft({ path: e.target.value })}
              />
              <TextField
                label="Provider"
                size="small"
                select
                fullWidth
                value={draft.provider}
                onChange={(e) => updateDraft({ provider: e.target.value as ProviderName })}
              >
                {PROVIDERS.map((p) => (
                  <MenuItem key={p} value={p}>
                    {p}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Model"
                size="small"
                fullWidth
                value={draft.model}
                onChange={(e) => updateDraft({ model: e.target.value })}
              />
              <TextField
                label="Text"
                size="small"
                fullWidth
                multiline
                minRows={2}
                maxRows={8}
                value={draft.text}
                onChange={(e) => updateDraft({ text: e.target.value })}
              />

              {/* Tool calls */}
              <Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                  <Typography variant="subtitle2">Tool Calls</Typography>
                  <IconButton size="small" onClick={addToolCall} aria-label="Add tool call">
                    <AddIcon fontSize="small" />
                  </IconButton>
                </Box>
                {draft.toolCalls.map((tc, i) => (
                  <Box key={i} sx={{ display: 'flex', gap: 1, mb: 1, alignItems: 'flex-start' }}>
                    <TextField
                      label="Name"
                      size="small"
                      value={tc.name}
                      onChange={(e) => updateToolCall(i, { name: e.target.value })}
                      sx={{ flex: 1 }}
                    />
                    <TextField
                      label="Arguments"
                      size="small"
                      value={tc.arguments ?? ''}
                      onChange={(e) => updateToolCall(i, { arguments: e.target.value })}
                      sx={{ flex: 2 }}
                    />
                    <IconButton
                      size="small"
                      onClick={() => removeToolCall(i)}
                      aria-label="Remove tool call"
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Box>
                ))}
              </Box>

              <TextField
                label="Stop Reason"
                size="small"
                fullWidth
                value={draft.stopReason}
                onChange={(e) => updateDraft({ stopReason: e.target.value })}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.streaming}
                    onChange={(e) => updateDraft({ streaming: e.target.checked })}
                  />
                }
                label="Streaming"
              />
            </Box>
          )}

          {/* Tab 1: JSON */}
          {tab === 1 && (
            <Box sx={{ position: 'relative' }}>
              <Box sx={{ position: 'absolute', top: 0, right: 0 }}>
                <CopyButton text={jsonOutput} />
              </Box>
              <Box
                component="pre"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.75rem',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  m: 0,
                  p: 1,
                  bgcolor: 'action.hover',
                  borderRadius: 1,
                  overflow: 'auto',
                  maxHeight: 400,
                }}
              >
                {jsonOutput}
              </Box>
            </Box>
          )}

          {/* Tab 2: Java */}
          {tab === 2 && (
            <Box sx={{ position: 'relative' }}>
              <Box sx={{ position: 'absolute', top: 0, right: 0 }}>
                <CopyButton text={javaOutput} />
              </Box>
              <Box
                component="pre"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.75rem',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  m: 0,
                  p: 1,
                  bgcolor: 'action.hover',
                  borderRadius: 1,
                  overflow: 'auto',
                  maxHeight: 400,
                }}
              >
                {javaOutput}
              </Box>
            </Box>
          )}

          {error && (
            <Alert severity="error" sx={{ mt: 2 }}>
              <Box
                component="pre"
                sx={{ fontFamily: 'monospace', fontSize: '0.7rem', whiteSpace: 'pre-wrap', m: 0 }}
              >
                {error}
              </Box>
            </Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="contained"
            onClick={() => void handleRegister()}
            disabled={registering || !draft.path || !draft.provider}
          >
            {registering ? 'Registering...' : 'Register'}
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={snackOpen}
        autoHideDuration={3000}
        onClose={() => setSnackOpen(false)}
        message="Mock expectation registered successfully"
      />
    </>
  );
}
