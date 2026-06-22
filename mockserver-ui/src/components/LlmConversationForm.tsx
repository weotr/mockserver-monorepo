import { useState, useCallback, useMemo } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Snackbar from '@mui/material/Snackbar';
import Divider from '@mui/material/Divider';
import type { ProviderName } from '../lib/expectationFromCapture';
import type {
  ConversationDraft,
  IsolationConfig,
  TurnDraft,
} from '../lib/conversationCodegen';
import {
  conversationToMcpArgs,
  draftFromScenarioExpectations,
  listConversationScenarios,
  hasRangeErrors,
} from '../lib/conversationCodegen';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import { humanizeError } from '../lib/errorMessage';
import { useDashboardStore } from '../store';
import ConversationWizardStep1 from './ConversationWizardStep1';
import ConversationWizardStep2 from './ConversationWizardStep2';
import ConversationWizardStep3 from './ConversationWizardStep3';
import { monospaceFontFamily } from '../theme';

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

export interface LlmConversationFormProps {
  connectionParams: { host: string; port: string; secure: boolean };
  /** When provided, the form pre-loads this scenario for editing. Pass the
   *  scenarioName via a `key` prop on the parent so React remounts when
   *  switching scenarios — this avoids needing a useEffect dance. */
  initialScenarioName?: string;
}

export default function LlmConversationForm({
  connectionParams,
  initialScenarioName,
}: LlmConversationFormProps) {
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);
  const scenarios = useMemo(
    () => listConversationScenarios(activeExpectations),
    [activeExpectations],
  );

  // Compute initial draft + ids once at mount based on initialScenarioName.
  // Parent remounts via `key` prop when the selection changes.
  const initial = useMemo(() => {
    if (initialScenarioName) {
      const scenario = scenarios.find((s) => s.scenarioName === initialScenarioName);
      if (scenario) {
        return draftFromScenarioExpectations(scenario.expectations);
      }
    }
    return { draft: emptyDraft(), ids: [] as string[] };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [draft, setDraft] = useState<ConversationDraft>(initial.draft);
  const [existingIds] = useState<string[]>(initial.ids);
  const editingScenario = initialScenarioName ?? '';
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [snackOpen, setSnackOpen] = useState(false);
  const [registrationResult, setRegistrationResult] = useState<Record<string, unknown> | null>(null);

  const handleRegister = useCallback(async () => {
    setRegistering(true);
    setError(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const idsToReuse =
        editingScenario && existingIds.length === draft.turns.length
          ? existingIds
          : undefined;

      // When editing an existing conversation and the turn count has changed,
      // the old expectations can't be reused 1:1. Clear them first so we don't
      // orphan a duplicate scenario with stale expectations.
      if (editingScenario && existingIds.length > 0 && !idsToReuse) {
        for (const oldId of existingIds) {
          const clearRes = await fetch(`${baseUrl}/mockserver/clear?type=expectations`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: oldId }),
          });
          // Abort if a clear fails (e.g. auth-enforced 403) — otherwise we'd
          // register the new turns on top of the un-cleared old ones, recreating
          // the duplicate-scenario bug this is meant to prevent.
          if (!clearRes.ok) {
            setError(`Failed to clear existing turn ${oldId} (HTTP ${clearRes.status}); aborted to avoid duplicating the conversation.`);
            setRegistering(false);
            return;
          }
        }
      }

      const args = conversationToMcpArgs(draft, idsToReuse);
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
      setError(humanizeError(err).message);
    } finally {
      setRegistering(false);
    }
  }, [connectionParams, draft, editingScenario, existingIds]);

  const canRegister = draft.path.trim().length > 0 && draft.turns.length > 0 && !hasRangeErrors(draft.turns);

  return (
    <>
      {/* Conversation basics */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
          1 · Conversation basics
        </Typography>
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
      </Paper>

      {/* Turns */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
          2 · Turns
        </Typography>
        <ConversationWizardStep2
          turns={draft.turns}
          onTurnsChange={(turns: TurnDraft[]) => setDraft((d) => ({ ...d, turns }))}
        />
      </Paper>

      {/* Review + Register */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle2" sx={{ fontSize: '0.78rem', fontWeight: 600, mb: 1, textTransform: 'uppercase', letterSpacing: 0.5, color: 'text.secondary' }}>
          3 · Review &amp; register
        </Typography>
        <Divider sx={{ mb: 1 }} />
        <ConversationWizardStep3 draft={draft} />
        <Box sx={{ mt: 2, display: 'flex', gap: 1, alignItems: 'center' }}>
          <Button
            variant="contained"
            size="small"
            onClick={() => void handleRegister()}
            disabled={registering || !canRegister}
          >
            {registering
              ? 'Registering…'
              : editingScenario
                ? existingIds.length === draft.turns.length
                  ? `Update ${existingIds.length} expectation${existingIds.length === 1 ? '' : 's'}`
                  : `Replace conversation (${existingIds.length} → ${draft.turns.length} turns)`
                : 'Register on server'}
          </Button>
          {editingScenario ? (
            existingIds.length === draft.turns.length ? (
              <Typography variant="caption" color="success.main" sx={{ fontSize: '0.7rem' }}>
                Editing — the existing expectation IDs will be reused so this updates in place.
              </Typography>
            ) : (
              <Typography variant="caption" color="warning.main" sx={{ fontSize: '0.7rem' }}>
                Turn count changed — the old expectations will be removed and replaced.
              </Typography>
            )
          ) : (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem' }}>
              Pick an existing conversation above to update it in place, or leave blank to create a new one.
            </Typography>
          )}
        </Box>
        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            <Box component="pre" sx={{ fontFamily: monospaceFontFamily, fontSize: '0.7rem', whiteSpace: 'pre-wrap', m: 0 }}>
              {error}
            </Box>
          </Alert>
        )}
        {registrationResult && (
          <Alert severity="success" sx={{ mt: 2 }}>
            <Typography variant="body2">
              Conversation registered.
              {Boolean(registrationResult['scenarioName']) && (
                <> Scenario: <code>{String(registrationResult['scenarioName'])}</code></>
              )}
            </Typography>
            {Array.isArray(registrationResult['states']) && (
              <Box component="pre" sx={{ fontFamily: monospaceFontFamily, fontSize: '0.7rem', whiteSpace: 'pre-wrap', m: 0, mt: 0.5 }}>
                {JSON.stringify(registrationResult['states'], null, 2)}
              </Box>
            )}
          </Alert>
        )}
      </Paper>

      <Snackbar
        open={snackOpen}
        autoHideDuration={4000}
        onClose={() => setSnackOpen(false)}
        message="Conversation registered"
      />
    </>
  );
}
