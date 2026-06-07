import { useCallback, useEffect, useRef, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Table from '@mui/material/Table';
import TableHead from '@mui/material/TableHead';
import TableBody from '@mui/material/TableBody';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import RefreshIcon from '@mui/icons-material/Refresh';
import { buildBaseUrl, callMcpTool } from '../lib/mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

interface McpTool {
  name?: string;
  description?: string;
  _mockserver?: { method?: string; path?: string; expectationId?: string };
}

interface McpToolsPanelProps {
  connectionParams: ConnectionParams;
  /**
   * Expectation id currently selected for editing in the composer. The tool derived from
   * this expectation is highlighted and scrolled into view to make the mock -> tool
   * relationship explicit.
   */
  selectedExpectationId?: string;
}

/**
 * Lists the Model Context Protocol tools generated from the current mock
 * expectations via the `list_mock_tools` MCP tool, so an agent's view of the
 * mocked endpoints is browsable from the dashboard.
 */
export default function McpToolsPanel({ connectionParams, selectedExpectationId }: McpToolsPanelProps) {
  const [tools, setTools] = useState<McpTool[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const selectedRowRef = useRef<HTMLTableRowElement | null>(null);

  // Refresh by bumping a tick (event-handler setState is fine); the effect re-runs and re-fetches.
  const refresh = useCallback(() => {
    setLoading(true);
    setRefreshTick((t) => t + 1);
  }, []);

  // Fetch defined inline in the effect with state mutated only after the await — mirrors
  // DriftPanel and keeps the effect free of synchronous setState.
  useEffect(() => {
    let cancelled = false;
    async function load(): Promise<void> {
      try {
        const res = await callMcpTool(buildBaseUrl(connectionParams), 'list_mock_tools', {});
        if (cancelled) return;
        if (!res.ok) {
          setError(typeof res.error === 'string' ? res.error : 'failed to generate MCP tools');
          setTools([]);
          return;
        }
        const result = res.result ?? {};
        const rawTools = Array.isArray(result.tools) ? (result.tools as McpTool[]) : [];
        setTools(rawTools);
        setError(null);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [connectionParams, refreshTick]);

  // Bring the highlighted tool into view when the selected expectation changes.
  useEffect(() => {
    if (selectedExpectationId && selectedRowRef.current) {
      selectedRowRef.current.scrollIntoView({ block: 'nearest' });
    }
  }, [selectedExpectationId, tools]);

  return (
    <Box sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <Typography variant="h6" sx={{ fontSize: '1rem', flex: 1 }}>
          MCP Tools{' '}
          <Chip label={tools.length} size="small" sx={{ ml: 0.5 }} />
        </Typography>
        <Button
          size="small"
          startIcon={loading ? <CircularProgress size={14} /> : <RefreshIcon fontSize="small" />}
          onClick={refresh}
          disabled={loading}
        >
          Refresh
        </Button>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        Model Context Protocol tool definitions generated from the active expectations — what an AI
        agent sees when it connects to this MockServer's MCP endpoint.
      </Typography>
      {error !== null && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}
      {!loading && error === null && tools.length === 0 && (
        <Alert severity="info">No tools — add response expectations to generate MCP tools.</Alert>
      )}
      {tools.length > 0 && (
        <Paper variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Tool name</TableCell>
                <TableCell>Endpoint</TableCell>
                <TableCell>Description</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {tools.map((tool, index) => {
                const isSelected =
                  selectedExpectationId !== undefined &&
                  tool._mockserver?.expectationId === selectedExpectationId;
                return (
                  <TableRow
                    key={tool.name ?? index}
                    ref={isSelected ? selectedRowRef : undefined}
                    selected={isSelected}
                    sx={isSelected ? { '& td': { fontWeight: 600 }, borderLeft: 3, borderLeftColor: 'primary.main' } : undefined}
                  >
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                      {tool.name ?? '—'}
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                      {tool._mockserver?.method ?? ''} {tool._mockserver?.path ?? ''}
                    </TableCell>
                    <TableCell>{tool.description ?? ''}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Paper>
      )}
    </Box>
  );
}
