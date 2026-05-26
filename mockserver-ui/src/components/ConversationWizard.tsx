import { useState, useCallback, useMemo } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Button from '@mui/material/Button';
import Stepper from '@mui/material/Stepper';
import Step from '@mui/material/Step';
import StepLabel from '@mui/material/StepLabel';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Snackbar from '@mui/material/Snackbar';
import Typography from '@mui/material/Typography';
import type { ProviderName } from '../lib/expectationFromCapture';
import type {
  ConversationDraft,
  IsolationConfig,
  TurnDraft,
} from '../lib/conversationCodegen';
import { conversationToMcpArgs } from '../lib/conversationCodegen';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import ConversationWizardStep1 from './ConversationWizardStep1';
import ConversationWizardStep2 from './ConversationWizardStep2';
import ConversationWizardStep3 from './ConversationWizardStep3';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface ConversationWizardProps {
  open: boolean;
  onClose: () => void;
  connectionParams: { host: string; port: string; secure: boolean };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const STEPS = ['Conversation basics', 'Turns', 'Review'];

function emptyDraft(): ConversationDraft {
  return {
    provider: 'ANTHROPIC',
    path: '/v1/messages',
    model: '',
    turns: [
      {
        predicates: { turnIndex: 0 },
        response: { text: '', toolCalls: [], stopReason: '', streaming: false },
      },
    ],
  };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ConversationWizard({
  open,
  onClose,
  connectionParams,
}: ConversationWizardProps) {
  const [activeStep, setActiveStep] = useState(0);
  const [draft, setDraft] = useState<ConversationDraft>(emptyDraft);
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [snackOpen, setSnackOpen] = useState(false);
  const [registrationResult, setRegistrationResult] = useState<Record<string, unknown> | null>(null);

  // Reset when reopening
  const [wasOpen, setWasOpen] = useState(false);
  if (open && !wasOpen) {
    setWasOpen(true);
    setActiveStep(0);
    setDraft(emptyDraft());
    setError(null);
    setRegistrationResult(null);
  }
  if (!open && wasOpen) {
    setWasOpen(false);
  }

  const handleNext = useCallback(() => {
    setActiveStep((s) => Math.min(s + 1, STEPS.length - 1));
  }, []);

  const handleBack = useCallback(() => {
    setActiveStep((s) => Math.max(s - 1, 0));
  }, []);

  const canProceed = useMemo(() => {
    if (activeStep === 0) {
      return draft.path.trim().length > 0;
    }
    if (activeStep === 1) {
      return draft.turns.length > 0;
    }
    return true;
  }, [activeStep, draft]);

  // Register via MCP
  const handleRegister = useCallback(async () => {
    setRegistering(true);
    setError(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const args = conversationToMcpArgs(draft);
      const result = await callMcpTool(baseUrl, 'create_llm_conversation', args);
      if (result.ok) {
        setSnackOpen(true);
        setRegistrationResult(result.result ?? null);
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
  }, [connectionParams, draft]);

  return (
    <>
      <Dialog
        open={open}
        onClose={onClose}
        fullScreen
        aria-labelledby="conversation-wizard-title"
      >
        <DialogTitle id="conversation-wizard-title" sx={{ pb: 1 }}>
          New LLM Conversation Mock
        </DialogTitle>
        <DialogContent dividers sx={{ display: 'flex', flexDirection: 'column' }}>
          <Stepper activeStep={activeStep} sx={{ mb: 3, flexShrink: 0 }}>
            {STEPS.map((label) => (
              <Step key={label}>
                <StepLabel>{label}</StepLabel>
              </Step>
            ))}
          </Stepper>

          <Box sx={{ flex: 1, overflowY: 'auto', px: 1 }}>
            {activeStep === 0 && (
              <ConversationWizardStep1
                provider={draft.provider}
                path={draft.path}
                model={draft.model}
                isolateBy={draft.isolateBy}
                onProviderChange={(provider: ProviderName) => setDraft((d) => ({ ...d, provider }))}
                onPathChange={(path: string) => setDraft((d) => ({ ...d, path }))}
                onModelChange={(model: string) => setDraft((d) => ({ ...d, model }))}
                onIsolateByChange={(isolateBy?: IsolationConfig) =>
                  setDraft((d) => ({ ...d, isolateBy }))
                }
              />
            )}
            {activeStep === 1 && (
              <ConversationWizardStep2
                turns={draft.turns}
                onTurnsChange={(turns: TurnDraft[]) => setDraft((d) => ({ ...d, turns }))}
              />
            )}
            {activeStep === 2 && <ConversationWizardStep3 draft={draft} />}
          </Box>

          {error && (
            <Alert severity="error" sx={{ mt: 2, flexShrink: 0 }}>
              <Box
                component="pre"
                sx={{ fontFamily: 'monospace', fontSize: '0.7rem', whiteSpace: 'pre-wrap', m: 0 }}
              >
                {error}
              </Box>
            </Alert>
          )}

          {registrationResult && (
            <Alert severity="success" sx={{ mt: 2, flexShrink: 0 }}>
              <Typography variant="body2">
                Conversation registered.
                {/* Boolean() coerces `unknown` to a JSX-renderable boolean so
                    the truthy-operand short-circuit type-checks under strict. */}
                {Boolean(registrationResult['scenarioName']) && (
                  <> Scenario: <code>{String(registrationResult['scenarioName'])}</code></>
                )}
              </Typography>
              {Array.isArray(registrationResult['states']) && (
                <Box
                  component="pre"
                  sx={{ fontFamily: 'monospace', fontSize: '0.7rem', whiteSpace: 'pre-wrap', m: 0, mt: 0.5 }}
                >
                  {JSON.stringify(registrationResult['states'], null, 2)}
                </Box>
              )}
            </Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Close</Button>
          <Box sx={{ flex: 1 }} />
          {activeStep > 0 && (
            <Button onClick={handleBack}>Back</Button>
          )}
          {activeStep < STEPS.length - 1 && (
            <Button variant="contained" onClick={handleNext} disabled={!canProceed}>
              Next
            </Button>
          )}
          {activeStep === STEPS.length - 1 && (
            <Button
              variant="contained"
              onClick={() => void handleRegister()}
              disabled={registering || draft.turns.length === 0}
            >
              {registering ? 'Registering...' : 'Register on server'}
            </Button>
          )}
        </DialogActions>
      </Dialog>

      <Snackbar
        open={snackOpen}
        autoHideDuration={4000}
        onClose={() => setSnackOpen(false)}
        message="LLM conversation mock registered successfully"
      />
    </>
  );
}
