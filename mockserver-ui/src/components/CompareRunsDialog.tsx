import { useState, useMemo, useCallback } from 'react';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Box from '@mui/material/Box';
import Alert from '@mui/material/Alert';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Paper from '@mui/material/Paper';
import { useDashboardStore } from '../store';
import { monospaceFontFamily } from '../theme';
import { groupBySession, shortenScenarioName, type Session } from '../lib/sessionGrouping';
import {
  extractTrajectory,
  compareTrajectories,
  type DiffReport,
  type TrajectorySkeleton,
} from '../lib/trajectoryDiff';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface CompareRunsDialogProps {
  open: boolean;
  onClose: () => void;
}

// ---------------------------------------------------------------------------
// Verdict banner
// ---------------------------------------------------------------------------

function VerdictBanner({ report }: { report: DiffReport }) {
  const severity = report.verdict === 'identical' ? 'success' : 'warning';
  let message: string;

  if (report.verdict === 'identical') {
    message = `IDENTICAL -- both runs have ${report.turnCountA} turn(s) with the same structural skeleton`;
  } else if (report.verdict === 'different-length') {
    message = `DIFFERENT LENGTHS -- Run A has ${report.turnCountA} turn(s), Run B has ${report.turnCountB} turn(s)`;
  } else {
    const d = report.firstDivergence;
    if (d) {
      message = `DIVERGENT at turn ${d.turn} (${d.kind}): Run A = "${d.a}" vs Run B = "${d.b}"`;
    } else {
      message = 'DIVERGENT';
    }
  }

  return (
    <Alert severity={severity} sx={{ mb: 2 }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: '0.8rem' }}>
        {message}
      </Typography>
    </Alert>
  );
}

// ---------------------------------------------------------------------------
// Tool call step chain
// ---------------------------------------------------------------------------

function ToolCallChain({
  label,
  turns,
}: {
  label: string;
  turns: TrajectorySkeleton['turns'];
}) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="caption" sx={{ fontWeight: 600, mb: 0.5, display: 'block' }}>
        {label}
      </Typography>
      <Box
        sx={{
          display: 'flex',
          gap: 0.5,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        {turns.map((turn, i) => {
          const toolLabel =
            turn.toolCalls.length > 0 ? turn.toolCalls.join(', ') : '(no tools)';
          const isError = turn.statusCode !== null && turn.statusCode >= 400;
          return (
            <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
              {i > 0 && (
                <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.7rem' }}>
                  {' -> '}
                </Typography>
              )}
              <Chip
                label={`[${i}] ${toolLabel}`}
                size="small"
                color={isError ? 'error' : 'default'}
                variant="outlined"
                sx={{
                  height: 20,
                  fontSize: '0.6rem',
                  fontFamily: monospaceFontFamily,
                  '& .MuiChip-label': { px: 0.5 },
                }}
              />
            </Box>
          );
        })}
        {turns.length === 0 && (
          <Typography variant="caption" color="text.secondary">
            (empty)
          </Typography>
        )}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Token trajectory table
// ---------------------------------------------------------------------------

function TokenTable({ report }: { report: DiffReport }) {
  if (report.tokenTrajectory.length === 0) return null;

  return (
    <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }}>Turn</TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              A Input
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              A Output
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              B Input
            </TableCell>
            <TableCell sx={{ fontWeight: 600, fontSize: '0.7rem' }} align="right">
              B Output
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {report.tokenTrajectory.map((entry) => (
            <TableRow key={entry.turn}>
              <TableCell sx={{ fontSize: '0.7rem' }}>{entry.turn}</TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.aInput ?? '-'}
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.aOutput ?? '-'}
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.bInput ?? '-'}
              </TableCell>
              <TableCell align="right" sx={{ fontSize: '0.7rem', fontFamily: monospaceFontFamily }}>
                {entry.bOutput ?? '-'}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

/**
 * Body component — usable inline (inside the Library/Runs tab) without
 * the Dialog chrome.
 */
export function CompareRunsBody() {
  // Compare Runs needs to see both upstream-proxied traffic and mock-matched
  // traffic, since users typically iterate on captured mocks rather than
  // running through a real upstream.
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);

  const allRequests = useMemo(
    () => [...proxiedRequests, ...recordedRequests],
    [proxiedRequests, recordedRequests],
  );

  const sessions = useMemo(
    () => groupBySession(allRequests, activeExpectations),
    [allRequests, activeExpectations],
  );

  const [runAKey, setRunAKey] = useState('');
  const [runBKey, setRunBKey] = useState('');

  const sessionKey = useCallback(
    (s: Session) => `${s.scenarioName}::${s.isolationKey}`,
    [],
  );

  const sessionLabel = useCallback(
    (s: Session) => {
      const name = shortenScenarioName(s.scenarioName);
      const isUnscoped = s.scenarioName === '<unscoped>';
      return isUnscoped
        ? `Unscoped (${s.requests.length} requests)`
        : `${name} / ${s.isolationKey} (${s.requests.length} requests)`;
    },
    [],
  );

  const runA = useMemo(
    () => sessions.find((s) => sessionKey(s) === runAKey) ?? null,
    [sessions, runAKey, sessionKey],
  );

  const runB = useMemo(
    () => sessions.find((s) => sessionKey(s) === runBKey) ?? null,
    [sessions, runBKey, sessionKey],
  );

  const report: DiffReport | null = useMemo(() => {
    if (!runA || !runB) return null;
    const trajA = extractTrajectory(runA);
    const trajB = extractTrajectory(runB);
    return compareTrajectories(trajA, trajB);
  }, [runA, runB]);

  const trajA = useMemo(() => (runA ? extractTrajectory(runA) : null), [runA]);
  const trajB = useMemo(() => (runB ? extractTrajectory(runB) : null), [runB]);

  return (
    <Box>
      {/* Session selectors */}
      <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
        <TextField
          label="Trace A"
          size="small"
          select
          fullWidth
          value={runAKey}
          onChange={(e) => setRunAKey(e.target.value)}
          slotProps={{
            select: { native: true, displayEmpty: true },
            inputLabel: { shrink: true },
          }}
        >
          <option value="">— select a trace —</option>
          {sessions.map((s) => (
            <option key={sessionKey(s)} value={sessionKey(s)}>
              {sessionLabel(s)}
            </option>
          ))}
        </TextField>
        <TextField
          label="Trace B"
          size="small"
          select
          fullWidth
          value={runBKey}
          onChange={(e) => setRunBKey(e.target.value)}
          slotProps={{
            select: { native: true, displayEmpty: true },
            inputLabel: { shrink: true },
          }}
        >
          <option value="">— select a trace —</option>
          {sessions.map((s) => (
            <option key={sessionKey(s)} value={sessionKey(s)}>
              {sessionLabel(s)}
            </option>
          ))}
        </TextField>
      </Box>

      {/* Empty / partial-selection state */}
      {(!runA || !runB) && (
        <Box sx={{ textAlign: 'center', py: 4 }}>
          <Typography variant="body2" color="text.secondary">
            {!runA && !runB && 'Choose two captured traces to compare.'}
            {runA && !runB && 'Trace A selected — choose Trace B to compare.'}
            {!runA && runB && 'Trace B selected — choose Trace A to compare.'}
          </Typography>
        </Box>
      )}

      {/* Comparison results */}
      {report && trajA && trajB && (
        <>
          <VerdictBanner report={report} />

          {/* Side-by-side tool call chains */}
          <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
            <ToolCallChain label="Run A" turns={trajA.turns} />
            <ToolCallChain label="Run B" turns={trajB.turns} />
          </Box>

          {/* Token trajectory table */}
          <Typography variant="subtitle2" sx={{ fontSize: '0.8rem', fontWeight: 600, mt: 2 }}>
            Token Usage per Turn
          </Typography>
          <TokenTable report={report} />
        </>
      )}
    </Box>
  );
}

export default function CompareRunsDialog({ open, onClose }: CompareRunsDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="lg"
      fullWidth
      fullScreen={fullScreen}
      aria-labelledby="compare-runs-title"
    >
      <DialogTitle id="compare-runs-title">Compare Runs</DialogTitle>
      <DialogContent dividers>
        <CompareRunsBody />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
