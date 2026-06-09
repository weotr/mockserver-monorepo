import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Alert from '@mui/material/Alert';
import CircularProgress from '@mui/material/CircularProgress';
import type { DiffResult, FieldDiff } from '../lib/diff';

interface DiffPanelProps {
  result: DiffResult | null;
  loading: boolean;
  error: string | null;
}

function diffTypeColor(diffType: string): 'success' | 'error' | 'warning' | 'default' {
  switch (diffType) {
    case 'ADDED':
      return 'success';
    case 'REMOVED':
      return 'error';
    case 'CHANGED':
      return 'warning';
    default:
      return 'default';
  }
}

export default function DiffPanel({ result, loading, error }: DiffPanelProps) {
  return (
    <Paper variant="outlined" sx={{ p: 1.25, mt: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          Request Diff
        </Typography>
        {result && (
          <Chip
            size="small"
            label={result.identical ? 'Identical' : `${result.diffCount} differences`}
            color={result.identical ? 'success' : 'warning'}
            variant="outlined"
          />
        )}
      </Box>

      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
          <CircularProgress size={24} />
        </Box>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}

      {result && !result.identical && result.diffs.length > 0 && (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Field</TableCell>
                <TableCell>Expected</TableCell>
                <TableCell>Actual</TableCell>
                <TableCell>Type</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {result.diffs.map((diff: FieldDiff, i: number) => (
                <TableRow key={`${diff.field}-${i}`}>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                      {diff.field}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: 'monospace', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' }}>
                      {diff.expectedValue ?? '-'}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: 'monospace', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' }}>
                      {diff.actualValue ?? '-'}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={diff.diffType}
                      color={diffTypeColor(diff.diffType)}
                      variant="outlined"
                      sx={{ height: 20, fontSize: '0.65rem' }}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {result && !result.identical && result.diffs.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 1 }}>
          Requests differ but no field-level diffs were produced by the server.
        </Typography>
      )}

      {result && result.identical && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 1 }}>
          The two requests are identical.
        </Typography>
      )}
    </Paper>
  );
}
