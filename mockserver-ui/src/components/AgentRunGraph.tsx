import { useCallback, useEffect, useId, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import Collapse from '@mui/material/Collapse';
import CircularProgress from '@mui/material/CircularProgress';
import CopyButton from './CopyButton';
import { callMcpTool, buildBaseUrl } from '../lib/mcpClient';
import { parseCallGraph, toMermaid, type CallGraph } from '../lib/callGraph';
import { humanizeError } from '../lib/errorMessage';
import { useDashboardStore } from '../store';

interface AgentRunGraphProps {
  connectionParams: { host: string; port: string; secure: boolean };
  provider: string;
  path: string | null;
  /**
   * Optional session-isolation scope. When all three are present and non-empty
   * they are forwarded to explain_agent_run so the server filters recorded
   * requests to a single session before building the call graph; otherwise the
   * graph is built globally (path-scoped only), which merges every session that
   * shares the endpoint. The three fields mirror the server contract:
   * isolationType (header|query_parameter|cookie), isolationKey (the
   * header/param/cookie name) and isolationValue (its value for this session).
   */
  isolationType?: string;
  isolationKey?: string;
  isolationValue?: string;
}

/**
 * "Show graph" affordance for a session: fetches the correlated agent-run call
 * graph via the explain_agent_run MCP tool and renders it as a real Mermaid SVG
 * diagram below the link. Read-only and deterministic — it visualises the
 * recorded conversation's structure (turns and the tool calls each made). Shown
 * beneath the chat-transcript Conversation view as a compact alternative
 * representation.
 *
 * Mermaid is large (~hundreds of kB), so it is loaded with a dynamic
 * `import('mermaid')` inside an effect — it stays out of the initial dashboard
 * bundle and lands in its own lazily-loaded chunk, only fetched the first time a
 * user opens a graph. If rendering fails (or mermaid cannot load) the component
 * falls back to showing the Mermaid source text so the data is never lost.
 */
export default function AgentRunGraph({
  connectionParams,
  provider,
  path,
  isolationType,
  isolationKey,
  isolationValue,
}: AgentRunGraphProps) {
  const [graph, setGraph] = useState<CallGraph | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [showSource, setShowSource] = useState(false);

  const themeMode = useDashboardStore((s) => s.themeMode);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const baseUrl = buildBaseUrl(connectionParams);
      const args: Record<string, unknown> = { provider };
      if (path) {
        args['path'] = path;
      }
      // Only forward the isolation scope when ALL three fields are present and
      // non-empty — a partial scope cannot be resolved to a session server-side
      // and would silently behave like the unscoped (merged) lookup.
      if (isolationType && isolationKey && isolationValue) {
        args['isolationType'] = isolationType;
        args['isolationKey'] = isolationKey;
        args['isolationValue'] = isolationValue;
      }
      const result = await callMcpTool(baseUrl, 'explain_agent_run', args);
      if (result.ok && result.result) {
        const parsed = parseCallGraph(result.result['callGraph']);
        if (parsed && parsed.nodes.length > 0) {
          setGraph(parsed);
        } else {
          setError('No call graph available for this session.');
        }
      } else {
        setError(typeof result.error === 'string' ? result.error : 'Failed to load call graph');
      }
    } catch (err) {
      setError(humanizeError(err).message);
    } finally {
      setLoading(false);
    }
  }, [connectionParams, provider, path, isolationType, isolationKey, isolationValue]);

  // First click opens (and loads the graph on demand); second click hides it. The
  // graph is read-only/deterministic, so a re-open shows the cached result.
  const toggle = useCallback(() => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (!graph && !loading) {
      void load();
    }
  }, [open, graph, loading, load]);

  // Source generation is unchanged — we only change how it is DISPLAYED.
  const source = useMemo(() => (graph ? toMermaid(graph) : ''), [graph]);

  return (
    <Box sx={{ mt: 0.5 }}>
      <Button
        size="small"
        onClick={toggle}
        disabled={loading}
        sx={{ textTransform: 'none', fontSize: '0.7rem', px: 0.5, minWidth: 0 }}
      >
        {loading ? 'Loading…' : open ? 'Hide graph' : 'Show graph'}
      </Button>
      <Collapse in={open} unmountOnExit>
        {error ? (
          <Typography variant="caption" color="error" sx={{ display: 'block', mt: 0.5 }}>
            {error}
          </Typography>
        ) : graph ? (
          <Box sx={{ mt: 0.5 }}>
            <MermaidDiagram source={source} themeMode={themeMode} showSource={showSource} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mt: 0.5 }}>
              <Button
                size="small"
                onClick={() => setShowSource((s) => !s)}
                sx={{ textTransform: 'none', fontSize: '0.65rem', px: 0.5, minWidth: 0 }}
              >
                {showSource ? 'Hide Mermaid source' : 'Show Mermaid source'}
              </Button>
              <CopyButton text={source} />
            </Box>
          </Box>
        ) : null}
      </Collapse>
    </Box>
  );
}

interface MermaidDiagramProps {
  source: string;
  themeMode: 'light' | 'dark';
  showSource: boolean;
}

type RenderState =
  | { status: 'rendering' }
  | { status: 'rendered'; svg: string }
  | { status: 'failed' };

/**
 * Renders a Mermaid `source` string to an inline SVG. Mermaid is imported
 * dynamically (lazy chunk) and re-rendered whenever the source or theme changes.
 * On any failure the parent's source-text fallback is shown instead, so the
 * diagram never becomes a dead end.
 */
function MermaidDiagram({ source, themeMode, showSource }: MermaidDiagramProps) {
  const [state, setState] = useState<RenderState>({ status: 'rendering' });
  // A DOM-id-safe unique id for mermaid.render (it injects a temporary element).
  const rawId = useId();
  const renderId = useMemo(() => `agent-run-graph-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`, [rawId]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!cancelled) {
        setState({ status: 'rendering' });
      }
      try {
        const mermaidModule = await import('mermaid');
        const mermaid = mermaidModule.default;
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'strict',
          theme: themeMode === 'dark' ? 'dark' : 'default',
        });
        const { svg } = await mermaid.render(renderId, source);
        if (!cancelled) {
          setState({ status: 'rendered', svg });
        }
      } catch (err) {
        if (!cancelled) {
          // The UI falls back to showing the Mermaid source; log the underlying
          // reason so a render failure can still be diagnosed.
          console.warn('AgentRunGraph: Mermaid render failed, showing source instead', err);
          setState({ status: 'failed' });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [source, themeMode, renderId]);

  // Fall back to the source text when rendering failed OR the user asked for it.
  const showSourceText = state.status === 'failed' || showSource;

  return (
    <Box>
      {state.status === 'rendering' && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 1 }}>
          <CircularProgress size={14} thickness={5} />
          <Typography variant="caption" color="text.secondary">
            Rendering graph…
          </Typography>
        </Box>
      )}
      {state.status === 'failed' && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          Could not render the diagram — showing the Mermaid source instead.
        </Typography>
      )}
      {state.status === 'rendered' && (
        <Box
          data-testid="agent-run-graph-svg"
          sx={{
            overflow: 'auto',
            maxHeight: 420,
            p: 1,
            bgcolor: 'action.hover',
            borderRadius: 1,
            '& svg': { maxWidth: '100%', height: 'auto' },
          }}
          // mermaid.render returns sanitized SVG (securityLevel: 'strict').
          dangerouslySetInnerHTML={{ __html: state.svg }}
        />
      )}
      {showSourceText && (
        <Box
          component="pre"
          sx={{
            fontSize: '0.7rem',
            overflow: 'auto',
            m: 0,
            mt: state.status === 'rendered' ? 0.5 : 0,
            p: 1,
            bgcolor: 'action.hover',
            borderRadius: 1,
          }}
        >
          {source}
        </Box>
      )}
    </Box>
  );
}
