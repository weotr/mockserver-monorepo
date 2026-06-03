import { useState, useCallback, useEffect, useRef } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Alert from '@mui/material/Alert';
import Tooltip from '@mui/material/Tooltip';
import RefreshIcon from '@mui/icons-material/Refresh';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import TimerIcon from '@mui/icons-material/Timer';
import {
  getScenarioState,
  setScenarioState,
  triggerScenario,
  listScenarios,
  type SetScenarioStateResponse,
  type ScenarioStateResponse,
} from '../lib/scenarios';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface ScenarioPanelProps {
  connectionParams: { host: string; port: string; secure: boolean };
}

// ---------------------------------------------------------------------------
// Countdown hook for timed transitions
// ---------------------------------------------------------------------------

// `nonce` increments each time a transition is (re)scheduled so an identical delay value still
// restarts the countdown — keying only on targetMs would silently keep the previous start time
// when the same delay is set twice in a row.
function useCountdown(targetMs: number | null, nonce: number): number | null {
  const [remaining, setRemaining] = useState<number | null>(null);
  const [prevNonce, setPrevNonce] = useState<number>(nonce);
  const startRef = useRef<number>(0);

  // Adjust state during render when the schedule changes — React's endorsed pattern
  // for resetting state on a prop change (avoids synchronous setState in an effect).
  if (nonce !== prevNonce) {
    setPrevNonce(nonce);
    setRemaining(targetMs != null && targetMs > 0 ? targetMs : null);
  }

  useEffect(() => {
    if (targetMs == null || targetMs <= 0) {
      return;
    }
    startRef.current = Date.now();
    const id = setInterval(() => {
      const elapsed = Date.now() - startRef.current;
      const left = Math.max(0, targetMs - elapsed);
      setRemaining(left);
      if (left <= 0) clearInterval(id);
    }, 250);

    return () => clearInterval(id);
  }, [targetMs, nonce]);

  return remaining;
}

function formatCountdown(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ScenarioPanel({ connectionParams }: ScenarioPanelProps) {
  // Query section
  const [scenarioName, setScenarioName] = useState('');
  const [currentState, setCurrentState] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Existing-scenarios list (populated from GET /mockserver/scenario)
  const [scenarios, setScenarios] = useState<ScenarioStateResponse[]>([]);
  const [listTick, setListTick] = useState(0);
  const refreshList = useCallback(() => setListTick((t) => t + 1), []);

  // Set state section
  const [newState, setNewState] = useState('');
  const [transitionAfterMs, setTransitionAfterMs] = useState('');
  const [nextState, setNextState] = useState('');

  // Trigger section
  const [triggerState, setTriggerState] = useState('');

  // Timed transition countdown
  const [scheduledTransitionMs, setScheduledTransitionMs] = useState<number | null>(null);
  const [scheduledNextState, setScheduledNextState] = useState<string | null>(null);
  const [transitionNonce, setTransitionNonce] = useState(0);
  const countdown = useCountdown(scheduledTransitionMs, transitionNonce);

  // Load the list of existing scenarios on mount and whenever refreshList() is called
  // (e.g. after setting a state creates a new scenario). setState only after the await,
  // guarded by `cancelled`, so we never call setState synchronously inside the effect.
  useEffect(() => {
    let cancelled = false;
    async function load(): Promise<void> {
      try {
        const next = await listScenarios(connectionParams);
        if (!cancelled) setScenarios(next);
      } catch {
        // A server without the list endpoint (older build) simply yields no list —
        // the query/set/trigger controls below still work by typed name.
        if (!cancelled) setScenarios([]);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [connectionParams, listTick]);

  const handleSelectScenario = useCallback((s: ScenarioStateResponse) => {
    setScenarioName(s.scenarioName);
    setCurrentState(s.currentState);
    setError(null);
  }, []);

  const handleRefresh = useCallback(async () => {
    if (!scenarioName.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const result = await getScenarioState(connectionParams, scenarioName.trim());
      setCurrentState(result.currentState);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setCurrentState(null);
    } finally {
      setLoading(false);
    }
  }, [connectionParams, scenarioName]);

  const handleSetState = useCallback(async () => {
    if (!scenarioName.trim() || !newState.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const ms = transitionAfterMs ? parseInt(transitionAfterMs, 10) : undefined;
      const next = nextState.trim() || undefined;
      const result: SetScenarioStateResponse = await setScenarioState(
        connectionParams,
        scenarioName.trim(),
        newState.trim(),
        ms,
        next,
      );
      setCurrentState(result.currentState);
      if (result.transitionAfterMs != null && result.nextState) {
        setScheduledTransitionMs(result.transitionAfterMs);
        setScheduledNextState(result.nextState);
        setTransitionNonce((n) => n + 1); // re-arm the countdown even if the delay is unchanged
      } else {
        setScheduledTransitionMs(null);
        setScheduledNextState(null);
      }
      refreshList();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, scenarioName, newState, transitionAfterMs, nextState, refreshList]);

  const handleTrigger = useCallback(async () => {
    if (!scenarioName.trim() || !triggerState.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const result = await triggerScenario(connectionParams, scenarioName.trim(), triggerState.trim());
      setCurrentState(result.currentState);
      setScheduledTransitionMs(null);
      setScheduledNextState(null);
      refreshList();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [connectionParams, scenarioName, triggerState, refreshList]);

  return (
    <Paper variant="outlined" sx={{ p: 1.5, mb: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.8rem' }}>
          Scenario State Machine
        </Typography>
        <Tooltip title="Refresh the list of existing scenarios">
          <span>
            <Button
              size="small"
              onClick={refreshList}
              startIcon={<RefreshIcon sx={{ fontSize: '0.875rem' }} />}
              sx={{ height: 24, fontSize: '0.65rem', textTransform: 'none', minWidth: 0 }}
            >
              List
            </Button>
          </span>
        </Tooltip>
      </Box>

      {/* Existing scenarios — click a chip to populate the query/set/trigger forms below */}
      <Box sx={{ mb: 1 }}>
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem', display: 'block', mb: 0.5 }}>
          Existing scenarios{scenarios.length > 0 ? ` (${scenarios.length})` : ''}
        </Typography>
        {scenarios.length === 0 ? (
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.65rem', fontStyle: 'italic' }}>
            None yet — set a state below (or match an expectation that uses one) to create a scenario.
          </Typography>
        ) : (
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
            {scenarios.map((s) => (
              <Tooltip key={s.scenarioName} title={`${s.scenarioName} → ${s.currentState}`}>
                <Chip
                  label={`${s.scenarioName}: ${s.currentState}`}
                  size="small"
                  variant={s.scenarioName === scenarioName ? 'filled' : 'outlined'}
                  color={s.scenarioName === scenarioName ? 'primary' : 'default'}
                  onClick={() => handleSelectScenario(s)}
                  sx={{ height: 20, fontSize: '0.6rem', fontFamily: 'monospace', maxWidth: 280 }}
                />
              </Tooltip>
            ))}
          </Box>
        )}
      </Box>

      {/* Scenario name + refresh */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <TextField
          size="small"
          placeholder="Scenario name"
          value={scenarioName}
          onChange={(e) => setScenarioName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void handleRefresh();
          }}
          sx={{
            flex: 1,
            '& .MuiInputBase-root': { height: 28, fontSize: '0.75rem' },
          }}
        />
        <Button
          variant="outlined"
          size="small"
          startIcon={<RefreshIcon sx={{ fontSize: '0.875rem' }} />}
          onClick={() => void handleRefresh()}
          disabled={loading || !scenarioName.trim()}
          sx={{ height: 28, fontSize: '0.7rem', textTransform: 'none' }}
        >
          Refresh
        </Button>
      </Box>

      {/* Current state display */}
      {currentState !== null && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem' }}>
            Current state:
          </Typography>
          <Chip
            label={currentState}
            size="small"
            color="primary"
            sx={{ height: 20, fontSize: '0.65rem', fontFamily: 'monospace' }}
          />
          {countdown !== null && countdown > 0 && scheduledNextState && (
            <Tooltip title={`Auto-transition to "${scheduledNextState}" in ${formatCountdown(countdown)}`}>
              <Chip
                icon={<TimerIcon sx={{ fontSize: '0.75rem' }} />}
                label={`${formatCountdown(countdown)} -> ${scheduledNextState}`}
                size="small"
                color="warning"
                variant="outlined"
                sx={{ height: 20, fontSize: '0.6rem', fontFamily: 'monospace' }}
              />
            </Tooltip>
          )}
        </Box>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 1, py: 0, '& .MuiAlert-message': { fontSize: '0.7rem' } }}>
          {error}
        </Alert>
      )}

      <Divider sx={{ my: 1 }} />

      {/* Set state form */}
      <Typography variant="caption" sx={{ fontWeight: 600, fontSize: '0.7rem', display: 'block', mb: 0.5 }}>
        Set State
      </Typography>
      <Box sx={{ display: 'flex', gap: 0.5, mb: 0.5, flexWrap: 'wrap' }}>
        <TextField
          size="small"
          placeholder="State"
          value={newState}
          onChange={(e) => setNewState(e.target.value)}
          sx={{
            flex: 1,
            minWidth: 80,
            '& .MuiInputBase-root': { height: 24, fontSize: '0.7rem' },
          }}
        />
        <Tooltip title="Optional auto-transition delay in milliseconds (e.g. 60000 = 1 minute). Pair with a Next state to schedule the transition.">
          <TextField
            size="small"
            placeholder="Delay (ms)"
            type="number"
            value={transitionAfterMs}
            onChange={(e) => setTransitionAfterMs(e.target.value)}
            sx={{
              width: 120,
              '& .MuiInputBase-root': { height: 24, fontSize: '0.7rem' },
            }}
          />
        </Tooltip>
        <TextField
          size="small"
          placeholder="Next state"
          value={nextState}
          onChange={(e) => setNextState(e.target.value)}
          sx={{
            flex: 1,
            minWidth: 80,
            '& .MuiInputBase-root': { height: 24, fontSize: '0.7rem' },
          }}
        />
        <Button
          variant="outlined"
          size="small"
          onClick={() => void handleSetState()}
          disabled={loading || !scenarioName.trim() || !newState.trim()}
          sx={{ height: 24, fontSize: '0.65rem', textTransform: 'none' }}
        >
          Set
        </Button>
      </Box>

      <Divider sx={{ my: 1 }} />

      {/* Trigger form */}
      <Typography variant="caption" sx={{ fontWeight: 600, fontSize: '0.7rem', display: 'block', mb: 0.5 }}>
        Trigger
      </Typography>
      <Box sx={{ display: 'flex', gap: 0.5, mb: 0.5 }}>
        <TextField
          size="small"
          placeholder="New state"
          value={triggerState}
          onChange={(e) => setTriggerState(e.target.value)}
          sx={{
            flex: 1,
            '& .MuiInputBase-root': { height: 24, fontSize: '0.7rem' },
          }}
        />
        <Button
          variant="outlined"
          size="small"
          startIcon={<PlayArrowIcon sx={{ fontSize: '0.75rem' }} />}
          onClick={() => void handleTrigger()}
          disabled={loading || !scenarioName.trim() || !triggerState.trim()}
          sx={{ height: 24, fontSize: '0.65rem', textTransform: 'none' }}
        >
          Trigger
        </Button>
      </Box>

      {/* Cross-protocol scenario legend (F15) */}
      <Divider sx={{ my: 1 }} />
      <Box sx={{ px: 0.5 }}>
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.65rem', display: 'block' }}>
          Cross-protocol correlation: Expectations with <code>crossProtocolScenarios</code> advance
          scenario state when matched, enabling coordinated behaviour across HTTP, gRPC, DNS, and
          SSE protocols. Triggers from one protocol can advance the state machine for expectations
          matched by another. Use the Trigger button above or the <code>/trigger</code> endpoint to
          manually advance a cross-protocol scenario.
        </Typography>
      </Box>
    </Paper>
  );
}
