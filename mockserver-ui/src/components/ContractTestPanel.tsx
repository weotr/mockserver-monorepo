import { useCallback, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import CircularProgress from '@mui/material/CircularProgress';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CancelIcon from '@mui/icons-material/Cancel';
import HumanErrorAlert from './HumanErrorAlert';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  runContractTest,
  type ContractTestReport,
  type ContractTestOperationResult,
} from '../lib/contractTest';

interface ContractTestPanelProps {
  connectionParams: ConnectionParams;
}

export default function ContractTestPanel({ connectionParams }: ContractTestPanelProps) {
  const [spec, setSpec] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [operationId, setOperationId] = useState('');
  const [running, setRunning] = useState(false);
  const [report, setReport] = useState<ContractTestReport | null>(null);
  const [error, setError] = useState<HumanError | null>(null);

  const canRun = spec.trim().length > 0 && baseUrl.trim().length > 0 && !running;

  const handleRun = useCallback(async () => {
    setRunning(true);
    setError(null);
    setReport(null);
    try {
      const result = await runContractTest(connectionParams, {
        spec: spec.trim(),
        baseUrl: baseUrl.trim(),
        operationId: operationId.trim() || undefined,
      });
      setReport(result);
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setRunning(false);
    }
  }, [connectionParams, spec, baseUrl, operationId]);

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Contract Test
        </Typography>
        {report && (
          <Chip
            size="small"
            label={report.allPassed ? 'All passed' : `${report.failed} failed`}
            color={report.allPassed ? 'success' : 'error'}
            variant="outlined"
          />
        )}
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Run an OpenAPI spec's operations against a live service and validate every response against the spec. Supply the spec as a URL, file path, or inline document.
      </Typography>

      <Paper variant="outlined" sx={{ p: 1.5, mb: 1.5 }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <TextField
            label="OpenAPI spec (URL or inline)"
            placeholder="https://example.com/openapi.json  —  or paste the spec here"
            value={spec}
            onChange={(e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setSpec(e.target.value)}
            multiline
            minRows={2}
            maxRows={10}
            fullWidth
            size="small"
          />
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5 }}>
            <TextField
              label="Target base URL"
              placeholder="http://localhost:8080"
              value={baseUrl}
              onChange={(e: ChangeEvent<HTMLInputElement>) => setBaseUrl(e.target.value)}
              size="small"
              sx={{ flex: '1 1 280px' }}
            />
            <TextField
              label="operationId (optional)"
              placeholder="all operations if blank"
              value={operationId}
              onChange={(e: ChangeEvent<HTMLInputElement>) => setOperationId(e.target.value)}
              size="small"
              sx={{ flex: '1 1 220px' }}
            />
          </Box>
          <Box>
            <Tooltip title={canRun ? 'Run the contract test' : 'A spec and a target base URL are required'}>
              <span>
                <Button
                  variant="contained"
                  size="small"
                  disabled={!canRun}
                  startIcon={running ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon fontSize="small" />}
                  onClick={() => { void handleRun(); }}
                >
                  {running ? 'Running…' : 'Run contract test'}
                </Button>
              </span>
            </Tooltip>
          </Box>
        </Box>
      </Paper>

      {error !== null && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}

      {report && (
        <Paper variant="outlined" sx={{ p: 1.25 }}>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, mb: 1.25 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {report.baseUrl}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <Chip size="small" variant="outlined" label={`${report.totalOperations} operation${report.totalOperations === 1 ? '' : 's'}`} />
            <Chip size="small" color="success" variant="outlined" label={`${report.passed} passed`} />
            <Chip
              size="small"
              color={report.failed > 0 ? 'error' : 'default'}
              variant="outlined"
              label={`${report.failed} failed`}
            />
          </Box>

          {report.results.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
              The spec produced no testable operations.
            </Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Result</TableCell>
                    <TableCell>Operation</TableCell>
                    <TableCell>Method</TableCell>
                    <TableCell>Path</TableCell>
                    <TableCell align="right">Status</TableCell>
                    <TableCell>Validation errors</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {report.results.map((result: ContractTestOperationResult, i: number) => (
                    <TableRow key={`${result.operationId}-${result.method}-${result.path}-${i}`}>
                      <TableCell>
                        {result.passed ? (
                          <Chip
                            size="small"
                            icon={<CheckCircleIcon fontSize="small" />}
                            label="PASS"
                            color="success"
                            variant="outlined"
                            sx={{ height: 22, fontSize: '0.65rem' }}
                          />
                        ) : (
                          <Chip
                            size="small"
                            icon={<CancelIcon fontSize="small" />}
                            label="FAIL"
                            color="error"
                            variant="outlined"
                            sx={{ height: 22, fontSize: '0.65rem' }}
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.operationId}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.method}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.path}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="caption">
                          {result.statusCodeReceived}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        {result.validationErrors.length === 0 ? (
                          <Typography variant="caption" color="text.secondary">
                            —
                          </Typography>
                        ) : (
                          <Box component="ul" sx={{ m: 0, pl: 2 }}>
                            {result.validationErrors.map((err, j) => (
                              <Typography
                                key={j}
                                component="li"
                                variant="caption"
                                color="error"
                                sx={{ fontFamily: 'monospace' }}
                              >
                                {err}
                              </Typography>
                            ))}
                          </Box>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Paper>
      )}
    </Box>
  );
}
