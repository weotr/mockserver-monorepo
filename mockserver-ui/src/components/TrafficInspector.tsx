import { useMemo, useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import InputAdornment from '@mui/material/InputAdornment';
import Chip from '@mui/material/Chip';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Divider from '@mui/material/Divider';
import Button from '@mui/material/Button';
import Tooltip from '@mui/material/Tooltip';
import Checkbox from '@mui/material/Checkbox';
import ToggleButton from '@mui/material/ToggleButton';
import SearchIcon from '@mui/icons-material/Search';
import SaveAltIcon from '@mui/icons-material/SaveAlt';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import { useDashboardStore } from '../store';
import { useConnectionParams } from '../hooks/useConnectionParams';
import JsonViewer from './JsonViewer';
import CaptureAsMockDialog from './CaptureAsMockDialog';
import DiffRequestsDialog from './DiffRequestsDialog';
import LlmUsageDetail from './LlmUsageDetail';
import {
  AnthropicConversationView,
  OpenAiConversationView,
  GeminiConversationView,
  OllamaConversationView,
  OpenAiResponsesConversationView,
  ScriptedTurnsPanel,
} from './ConversationView';
import type { ScriptedTurn } from './ConversationView';
import type { JsonListItem } from '../types';
import { isCapturableTraffic } from '../lib/expectationFromCapture';
import {
  summarizeTraffic,
  getModelLabel,
  getTokenSummary,
  getTimingLabel,
  type TrafficSummary,
  type ParsedTraffic,
  type RequestTiming,
  type AnthropicParsed,
  type OpenAiParsed,
  type OpenAiResponsesParsed,
  type GeminiParsed,
  type OllamaParsed,
  type McpParsed,
  type SseEvent,
} from '../lib/llmTraffic';

// ---------------------------------------------------------------------------
// Helper: status color
// ---------------------------------------------------------------------------

function statusColor(code: number | null): 'success' | 'error' | 'warning' | 'default' {
  if (code === null) return 'default';
  if (code >= 200 && code < 300) return 'success';
  if (code >= 400) return 'error';
  if (code >= 300) return 'warning';
  return 'default';
}

function kindLabel(parsed: ParsedTraffic): string {
  switch (parsed.kind) {
    case 'anthropic': return 'Anthropic';
    case 'openai': return 'OpenAI';
    case 'openai_responses': return 'OpenAI Resp';
    case 'gemini': return 'Gemini';
    case 'ollama': return 'Ollama';
    case 'mcp': return 'MCP';
    case 'generic': return 'HTTP';
  }
}

function kindColor(parsed: ParsedTraffic): 'primary' | 'secondary' | 'info' | 'default' {
  switch (parsed.kind) {
    case 'anthropic': return 'primary';
    case 'openai': return 'secondary';
    case 'openai_responses': return 'secondary';
    case 'gemini': return 'info';
    case 'ollama': return 'info';
    case 'mcp': return 'info';
    case 'generic': return 'default';
  }
}

// ---------------------------------------------------------------------------
// Scripted turns extraction from active expectations
// ---------------------------------------------------------------------------

const SCENARIO_STATE_ORDER: Record<string, number> = { Started: 0 };

function scenarioStateSortKey(state: string): number {
  if (state in SCENARIO_STATE_ORDER) return SCENARIO_STATE_ORDER[state]!;
  const match = /turn_(\d+)/.exec(state);
  if (match) return parseInt(match[1]!, 10) + 1;
  if (state === '__done') return 999999;
  return 500000; // unknown states sort near the end
}

/**
 * Gather scripted turns from expectations sharing the same scenarioName.
 * Each expectation with httpLlmResponse + conversationPredicates within the
 * same scenario constitutes one turn.
 */
function gatherScriptedTurns(expectations: JsonListItem[]): ScriptedTurn[] {
  // Group expectations by scenarioName. Only LLM expectations are considered;
  // a single shared scenarioName forms one conversation.
  const scenarioGroups = new Map<string, JsonListItem[]>();
  for (const exp of expectations) {
    const llm = exp.value['httpLlmResponse'] as Record<string, unknown> | undefined;
    if (!llm) continue;
    // scenarioName is a top-level Expectation field (ExpectationDTO), not nested inside
    // httpLlmResponse — read it from the top level (consistent with sessionGrouping).
    const scenarioName = exp.value['scenarioName'] as string | undefined;
    if (!scenarioName) continue;
    if (!scenarioGroups.has(scenarioName)) {
      scenarioGroups.set(scenarioName, []);
    }
    scenarioGroups.get(scenarioName)!.push(exp);
  }

  // Collect turns from EVERY eligible scenario group. The previous version
  // returned the first matching group and silently dropped the rest, which
  // produced incorrect output when multiple conversations were loaded
  // simultaneously. We now flatten across groups; the ScriptedTurnsPanel
  // displays a separator between conversations via the scenarioName field.
  const allTurns: ScriptedTurn[] = [];
  for (const [scenarioName, group] of scenarioGroups) {
    const hasPredicates = group.some((e) => {
      const llm = e.value['httpLlmResponse'] as Record<string, unknown> | undefined;
      return llm && 'conversationPredicates' in llm;
    });
    if (!hasPredicates && group.length < 2) continue;

    // Sort by scenario state transition order. scenarioState is a top-level Expectation field.
    const sorted = [...group].sort((a, b) => {
      const aState = (a.value['scenarioState'] as string | undefined) ?? 'Started';
      const bState = (b.value['scenarioState'] as string | undefined) ?? 'Started';
      return scenarioStateSortKey(aState) - scenarioStateSortKey(bState);
    });

    const turns: ScriptedTurn[] = sorted.map((exp, i) => {
      const llm = exp.value['httpLlmResponse'] as Record<string, unknown>;
      const predicates = (llm['conversationPredicates'] as Record<string, unknown>) ?? {};
      const completion = (llm['completion'] as Record<string, unknown>) ?? {};
      const toolCalls = (completion['toolCalls'] as Array<{ name: string; arguments?: string }>) ?? [];

      return {
        turnIndex: (predicates['turnIndex'] as number | undefined) ?? i,
        predicates: Object.fromEntries(
          Object.entries(predicates).filter(([, v]) => v != null),
        ),
        response: {
          text: (completion['text'] as string | undefined),
          toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
          stopReason: (completion['stopReason'] as string | undefined)
            ?? (llm['stopReason'] as string | undefined),
          // streaming lives inside completion (Completion.streaming), not at the llm top level.
          streaming: (completion['streaming'] as boolean | undefined),
        },
        // scenarioState / newScenarioState are top-level Expectation fields.
        scenarioState: (exp.value['scenarioState'] as string | undefined) ?? 'Started',
        newScenarioState: (exp.value['newScenarioState'] as string | undefined) ?? '__done',
        scenarioName,
      };
    });

    allTurns.push(...turns);
  }
  return allTurns;
}

// ---------------------------------------------------------------------------
// Search match helper
// ---------------------------------------------------------------------------

function matchesSearch(item: JsonListItem, summary: TrafficSummary, term: string): boolean {
  const lower = term.toLowerCase();
  const parts = [
    summary.host,
    summary.method,
    summary.path,
    summary.statusCode?.toString(),
    getModelLabel(summary.parsed),
    kindLabel(summary.parsed),
  ].filter(Boolean);
  if (parts.some((p) => p!.toLowerCase().includes(lower))) return true;
  return JSON.stringify(item.value).toLowerCase().includes(lower);
}

// ---------------------------------------------------------------------------
// Master list row
// ---------------------------------------------------------------------------

interface TrafficRowProps {
  summary: TrafficSummary;
  index: number;
  selected: boolean;
  onClick: () => void;
  /** When set, a comparison checkbox is rendered and reflects this checked state. */
  compareMode?: boolean;
  compareChecked?: boolean;
  compareDisabled?: boolean;
  onCompareToggle?: () => void;
}

function TrafficRow({
  summary,
  index,
  selected,
  onClick,
  compareMode,
  compareChecked,
  compareDisabled,
  onCompareToggle,
}: TrafficRowProps) {
  const model = getModelLabel(summary.parsed);
  const tokens = getTokenSummary(summary.parsed);
  const timingLabel = getTimingLabel(summary.timing);

  return (
    <Box
      onClick={onClick}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 0.75,
        px: 1,
        py: 0.5,
        cursor: 'pointer',
        bgcolor: selected ? 'action.selected' : 'transparent',
        '&:hover': { bgcolor: selected ? 'action.selected' : 'action.hover' },
        borderBottom: 1,
        borderColor: 'divider',
        minHeight: 36,
        flexWrap: 'wrap',
      }}
    >
      {compareMode && (
        <Checkbox
          size="small"
          checked={!!compareChecked}
          disabled={!compareChecked && compareDisabled}
          onClick={(e) => e.stopPropagation()}
          onChange={() => onCompareToggle?.()}
          slotProps={{ input: { 'aria-label': `Select request ${index} to compare` } }}
          sx={{ p: 0.25, flexShrink: 0 }}
        />
      )}
      <Typography
        variant="caption"
        sx={{ fontFamily: 'monospace', color: 'text.secondary', minWidth: 24, flexShrink: 0 }}
      >
        {index}
      </Typography>
      <Chip
        label={kindLabel(summary.parsed)}
        size="small"
        color={kindColor(summary.parsed)}
        variant="outlined"
        sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
      />
      <Typography
        variant="caption"
        sx={{
          fontFamily: 'monospace',
          fontWeight: 600,
          color: 'primary.main',
          flexShrink: 0,
        }}
      >
        {summary.method ?? '?'}
      </Typography>
      <Typography
        variant="caption"
        noWrap
        sx={{
          fontFamily: 'monospace',
          flex: 1,
          minWidth: 80,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {summary.host ? `${summary.host}` : ''}{summary.path ?? ''}
      </Typography>
      {summary.statusCode !== null && (
        <Chip
          label={summary.statusCode}
          size="small"
          color={statusColor(summary.statusCode)}
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
      {model && (
        <Chip
          label={model}
          size="small"
          variant="outlined"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
      {tokens && (
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.6rem', flexShrink: 0 }}>
          {tokens}
        </Typography>
      )}
      {timingLabel && (
        <Chip
          label={timingLabel}
          size="small"
          variant="outlined"
          color="info"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: Anthropic
// ---------------------------------------------------------------------------

function AnthropicMessagesPanel({ parsed }: { parsed: AnthropicParsed }) {
  return (
    <Box>
      {parsed.system != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>System</Typography>
          <Box sx={{ mt: 0.5 }}>
            {typeof parsed.system === 'string' ? (
              <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.75rem', whiteSpace: 'pre-wrap' }}>
                {parsed.system}
              </Typography>
            ) : (
              <JsonViewer data={parsed.system as Record<string, unknown>} collapsed={2} />
            )}
          </Box>
        </Box>
      )}
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.tools && parsed.tools.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Tools ({parsed.tools.length})</Typography>
          <JsonViewer data={parsed.tools as unknown[]} collapsed={1} />
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.maxTokens !== null && <Chip label={`Max tokens: ${parsed.maxTokens}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: OpenAI
// ---------------------------------------------------------------------------

function OpenAiMessagesPanel({ parsed }: { parsed: OpenAiParsed }) {
  return (
    <Box>
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.tools && parsed.tools.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Tools ({parsed.tools.length})</Typography>
          <JsonViewer data={parsed.tools as unknown[]} collapsed={1} />
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// MCP panel (content only, no wrapping tabs)
// ---------------------------------------------------------------------------

function McpDetailPanel({ parsed }: { parsed: McpParsed }) {
  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 1 }}>
        <Chip label="MCP JSON-RPC" size="small" color="info" variant="outlined" />
        {parsed.method && <Chip label={`Method: ${parsed.method}`} size="small" variant="outlined" />}
        {parsed.id != null && <Chip label={`ID: ${String(parsed.id)}`} size="small" variant="outlined" />}
      </Box>
      {parsed.params != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Params</Typography>
          <JsonViewer data={parsed.params as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
      {parsed.result != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Result</Typography>
          <JsonViewer data={parsed.result as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
      {parsed.error != null && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="caption" color="error" sx={{ fontWeight: 600 }}>Error</Typography>
          <JsonViewer data={parsed.error as Record<string, unknown>} collapsed={2} />
        </Box>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: OpenAI Responses API
// ---------------------------------------------------------------------------

function OpenAiResponsesMessagesPanel({ parsed }: { parsed: OpenAiResponsesParsed }) {
  return (
    <Box>
      {parsed.input.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Input ({parsed.input.length})</Typography>
          {parsed.input.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.output.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Output ({parsed.output.length})</Typography>
          {parsed.output.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: Gemini
// ---------------------------------------------------------------------------

function GeminiMessagesPanel({ parsed }: { parsed: GeminiParsed }) {
  return (
    <Box>
      {parsed.contents.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Contents ({parsed.contents.length})</Typography>
          {parsed.contents.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.candidates.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Candidates ({parsed.candidates.length})</Typography>
          {parsed.candidates.map((item, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={item as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Messages panel: Ollama
// ---------------------------------------------------------------------------

function OllamaMessagesPanel({ parsed }: { parsed: OllamaParsed }) {
  return (
    <Box>
      {parsed.messages.length > 0 && (
        <Box>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Messages ({parsed.messages.length})</Typography>
          {parsed.messages.map((msg, i) => (
            <Box key={i} sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
              <JsonViewer data={msg as Record<string, unknown>} collapsed={1} />
            </Box>
          ))}
        </Box>
      )}
      {parsed.responseMessage != null && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Response</Typography>
          <Box sx={{ mt: 0.5, pl: 1, borderLeft: 2, borderColor: 'divider' }}>
            <JsonViewer data={parsed.responseMessage as Record<string, unknown>} collapsed={1} />
          </Box>
        </Box>
      )}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {parsed.model && <Chip label={`Model: ${parsed.model}`} size="small" variant="outlined" />}
        {parsed.stream && <Chip label="Streaming" size="small" color="info" variant="outlined" />}
        {parsed.done && <Chip label="Done" size="small" color="success" variant="outlined" />}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// SSE Timeline
// ---------------------------------------------------------------------------

function SseTimeline({ events }: { events: SseEvent[] }) {
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);

  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, mb: 0.5, display: 'block' }}>
        SSE Events ({events.length})
      </Typography>
      {events.map((evt, i) => {
        const parsedData = (() => {
          if (evt.data === '[DONE]') return null;
          try { return JSON.parse(evt.data); } catch { return null; }
        })();

        return (
          <Box
            key={i}
            sx={{
              borderLeft: 2,
              borderColor: evt.event === 'error' ? 'error.main' : 'primary.main',
              pl: 1,
              py: 0.25,
              mb: 0.25,
              cursor: 'pointer',
              '&:hover': { bgcolor: 'action.hover' },
            }}
            onClick={() => setExpandedIndex(expandedIndex === i ? null : i)}
          >
            <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
              <Typography variant="caption" sx={{ fontFamily: 'monospace', color: 'text.secondary', minWidth: 24 }}>
                {i + 1}
              </Typography>
              {evt.event && (
                <Chip
                  label={evt.event}
                  size="small"
                  variant="outlined"
                  sx={{ height: 16, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
                />
              )}
              <Typography
                variant="caption"
                noWrap
                sx={{ fontFamily: 'monospace', fontSize: '0.65rem', color: 'text.secondary', flex: 1 }}
              >
                {evt.data.length > 80 ? evt.data.slice(0, 80) + '...' : evt.data}
              </Typography>
            </Box>
            {expandedIndex === i && parsedData && (
              <Box sx={{ mt: 0.5, ml: 3 }}>
                <JsonViewer data={parsedData} collapsed={2} />
              </Box>
            )}
            {expandedIndex === i && !parsedData && (
              <Typography variant="body2" sx={{ mt: 0.5, ml: 3, fontFamily: 'monospace', fontSize: '0.7rem', whiteSpace: 'pre-wrap' }}>
                {evt.data}
              </Typography>
            )}
          </Box>
        );
      })}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Per-request timing waterfall
// ---------------------------------------------------------------------------

function TimingWaterfall({ timing }: { timing: RequestTiming }) {
  const total = timing.totalTimeInMillis;
  if (total === null || total === 0) return null;

  const connect = timing.connectionTimeInMillis ?? 0;
  // Wait/TTFB is the gap between connection established and first byte
  const ttfb = timing.timeToFirstByteInMillis ?? 0;
  const waitMs = Math.max(0, ttfb - connect);
  const receiveMs = Math.max(0, total - ttfb);

  // Calculate percentages for the waterfall bar
  const connectPct = (connect / total) * 100;
  const waitPct = (waitMs / total) * 100;
  const receivePct = (receiveMs / total) * 100;

  return (
    <Box
      data-testid="timing-waterfall"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 0.5,
        px: 1,
        py: 0.5,
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: 'action.hover',
        flexShrink: 0,
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
        <Typography variant="caption" sx={{ fontSize: '0.65rem', fontWeight: 600, color: 'text.primary' }}>
          Timing
        </Typography>
        {timing.connectionTimeInMillis !== null && (
          <Chip
            label={`connect ${timing.connectionTimeInMillis}ms`}
            size="small"
            variant="outlined"
            sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
          />
        )}
        {timing.timeToFirstByteInMillis !== null && (
          <Chip
            label={`TTFB ${timing.timeToFirstByteInMillis}ms`}
            size="small"
            variant="outlined"
            sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
          />
        )}
        <Chip
          label={`total ${total}ms`}
          size="small"
          variant="outlined"
          color="info"
          sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
        />
      </Box>
      {/* Inline waterfall bar: connect -> wait/TTFB -> receive */}
      <Tooltip
        title={`Connect: ${connect}ms | Wait: ${waitMs}ms | Receive: ${receiveMs}ms`}
        placement="top"
        arrow
      >
        <Box
          data-testid="timing-bar"
          sx={{
            display: 'flex',
            height: 8,
            borderRadius: 1,
            overflow: 'hidden',
            bgcolor: 'background.default',
          }}
        >
          {connectPct > 0 && (
            <Box
              sx={{
                width: `${connectPct}%`,
                bgcolor: 'warning.main',
                minWidth: connectPct > 0 ? 2 : 0,
              }}
            />
          )}
          {waitPct > 0 && (
            <Box
              sx={{
                width: `${waitPct}%`,
                bgcolor: 'info.main',
                minWidth: waitPct > 0 ? 2 : 0,
              }}
            />
          )}
          {receivePct > 0 && (
            <Box
              sx={{
                width: `${receivePct}%`,
                bgcolor: 'success.main',
                minWidth: receivePct > 0 ? 2 : 0,
              }}
            />
          )}
        </Box>
      </Tooltip>
      <Box sx={{ display: 'flex', gap: 1.5, justifyContent: 'flex-start' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'warning.main' }} />
          <Typography variant="caption" sx={{ fontSize: '0.55rem', color: 'text.secondary' }}>Connect</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'info.main' }} />
          <Typography variant="caption" sx={{ fontSize: '0.55rem', color: 'text.secondary' }}>Wait</Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'success.main' }} />
          <Typography variant="caption" sx={{ fontSize: '0.55rem', color: 'text.secondary' }}>Receive</Typography>
        </Box>
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Detail pane wrapper — single-level, adaptive tab row
// ---------------------------------------------------------------------------

interface DetailPaneProps {
  item: JsonListItem;
  summary: TrafficSummary;
  scriptedTurns: ScriptedTurn[];
  onCaptureAsMock?: () => void;
}

/** Build the tab list dynamically from the traffic kind. */
function buildTabs(parsed: ParsedTraffic, hasScriptedTurns: boolean): string[] {
  switch (parsed.kind) {
    case 'anthropic':
    case 'openai':
    case 'openai_responses':
    case 'gemini':
    case 'ollama': {
      const tabs = ['Messages', 'Conversation'];
      if (hasScriptedTurns) tabs.push('Scripted Turns');
      if (parsed.sseEvents) tabs.push('SSE Timeline');
      tabs.push('Raw JSON');
      return tabs;
    }
    case 'mcp':
      return ['MCP', 'Raw JSON'];
    case 'generic':
      return []; // no tabs — render Raw JSON directly
  }
}

function DetailPane({ item, summary, scriptedTurns, onCaptureAsMock }: DetailPaneProps) {
  const tabs = buildTabs(summary.parsed, scriptedTurns.length > 0);
  const [detailTab, setDetailTab] = useState(0);
  const canCapture = isCapturableTraffic(summary.parsed);

  // For generic traffic, render Raw JSON directly — no tab bar needed
  if (tabs.length === 0) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
        <LlmUsageDetail parsed={summary.parsed} />
        {summary.timing && <TimingWaterfall timing={summary.timing} />}
        <Box sx={{ display: 'flex', alignItems: 'center', px: 1, py: 0.5, flexShrink: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, fontSize: '0.75rem', flexGrow: 1 }}>
            Raw JSON
          </Typography>
          {canCapture && onCaptureAsMock && (
            <Button
              size="small"
              startIcon={<SaveAltIcon sx={{ fontSize: '0.875rem' }} />}
              onClick={onCaptureAsMock}
              sx={{ fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap', flexShrink: 0 }}
            >
              Capture as mock
            </Button>
          )}
        </Box>
        <Divider />
        <Box sx={{ flex: 1, overflowY: 'auto', p: 1 }}>
          <JsonViewer data={item.value} collapsed={2} />
        </Box>
      </Box>
    );
  }

  // Clamp tab index defensively (e.g. if tabs array shrank before the effect runs)
  const safeTab = Math.min(detailTab, tabs.length - 1);
  const activeLabel = tabs[safeTab];

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      <LlmUsageDetail parsed={summary.parsed} />
      {summary.timing && <TimingWaterfall timing={summary.timing} />}
      <Box sx={{ display: 'flex', alignItems: 'center', flexShrink: 0 }}>
        <Tabs
          value={safeTab}
          onChange={(_, v: number) => setDetailTab(v)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{ flexGrow: 1, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.75rem' } }}
        >
          {tabs.map((label) => (
            <Tab key={label} label={label} />
          ))}
        </Tabs>
        {canCapture && onCaptureAsMock && (
          <Button
            size="small"
            startIcon={<SaveAltIcon sx={{ fontSize: '0.875rem' }} />}
            onClick={onCaptureAsMock}
            sx={{ mr: 0.5, fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap', flexShrink: 0 }}
          >
            Capture as mock
          </Button>
        )}
      </Box>
      <Divider />
      <Box sx={{ flex: 1, overflowY: 'auto', p: 1, minHeight: 0 }}>
        {activeLabel === 'Messages' && summary.parsed.kind === 'anthropic' && (
          <AnthropicMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'openai' && (
          <OpenAiMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'openai_responses' && (
          <OpenAiResponsesMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'gemini' && (
          <GeminiMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Messages' && summary.parsed.kind === 'ollama' && (
          <OllamaMessagesPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'anthropic' && (
          <AnthropicConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'openai' && (
          <OpenAiConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'openai_responses' && (
          <OpenAiResponsesConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'gemini' && (
          <GeminiConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'Conversation' && summary.parsed.kind === 'ollama' && (
          <OllamaConversationView parsed={summary.parsed} />
        )}
        {activeLabel === 'Scripted Turns' && scriptedTurns.length > 0 && (
          <ScriptedTurnsPanel turns={scriptedTurns} />
        )}
        {activeLabel === 'SSE Timeline' && 'sseEvents' in summary.parsed && summary.parsed.sseEvents && (
          <SseTimeline events={summary.parsed.sseEvents} />
        )}
        {activeLabel === 'MCP' && summary.parsed.kind === 'mcp' && (
          <McpDetailPanel parsed={summary.parsed} />
        )}
        {activeLabel === 'Raw JSON' && (
          <JsonViewer data={item.value} collapsed={2} />
        )}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main TrafficInspector component
// ---------------------------------------------------------------------------

export default function TrafficInspector() {
  // Show every captured request, regardless of whether it was proxied through to
  // a real upstream or matched a registered mock expectation. The MockServer
  // backend logs the two cases into separate arrays (proxiedRequests vs
  // recordedRequests); the user thinks of them both as "traffic".
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);
  const recordedRequests = useDashboardStore((s) => s.recordedRequests);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);
  const trafficSearch = useDashboardStore((s) => s.trafficSearch);
  const setTrafficSearch = useDashboardStore((s) => s.setTrafficSearch);
  const selectedKey = useDashboardStore((s) => s.selectedTrafficKey);
  const setSelectedKey = useDashboardStore((s) => s.setSelectedTrafficKey);
  const connectionParams = useConnectionParams();
  const [captureDialogOpen, setCaptureDialogOpen] = useState(false);

  // Compare mode: pick two requests from the list and diff them field-by-field via the shared
  // DiffRequestsDialog (PUT /mockserver/diff). compareKeys holds the (max two) selected item keys.
  const [compareMode, setCompareMode] = useState(false);
  const [compareKeys, setCompareKeys] = useState<string[]>([]);
  const [diffDialogOpen, setDiffDialogOpen] = useState(false);

  const toggleCompareMode = useCallback(() => {
    setCompareMode((prev) => {
      // Leaving compare mode clears any pending selection.
      if (prev) setCompareKeys([]);
      return !prev;
    });
  }, []);

  const toggleCompareKey = useCallback((key: string) => {
    setCompareKeys((prev) => {
      if (prev.includes(key)) return prev.filter((k) => k !== key);
      if (prev.length >= 2) return prev; // cap at two; row checkbox is disabled past this
      return [...prev, key];
    });
  }, []);

  // Gather scripted turns from active expectations
  const scriptedTurns = useMemo(
    () => gatherScriptedTurns(activeExpectations),
    [activeExpectations],
  );

  // Build summaries for every captured request (proxied + mocked).
  const allRequests = useMemo(
    () => [...proxiedRequests, ...recordedRequests],
    [proxiedRequests, recordedRequests],
  );
  const summaries = useMemo(
    () => allRequests.map((item) => ({ item, summary: summarizeTraffic(item.value) })),
    [allRequests],
  );

  // Filter by search
  const filtered = useMemo(
    () =>
      trafficSearch
        ? summaries.filter(({ item, summary }) => matchesSearch(item, summary, trafficSearch))
        : summaries,
    [summaries, trafficSearch],
  );

  // Select by stable item key, not array position: the store fully replaces the traffic lists
  // on every WebSocket refresh and `filtered` is re-derived on each search keystroke, so a
  // positional index would point at a different (or missing) request after any update.
  const selectedEntry = useMemo(() => {
    if (selectedKey === null) return null;
    return filtered.find(({ item }) => item.key === selectedKey) ?? null;
  }, [filtered, selectedKey]);

  const handleRowClick = useCallback(
    (key: string) => {
      setSelectedKey(selectedKey === key ? null : key);
    },
    [selectedKey, setSelectedKey],
  );

  // Resolve the two selected requests to the JSON the diff endpoint expects (the request
  // definition — `httpRequest` if present, otherwise the whole captured value). Preserve the
  // user's pick order: the first selected is "expected", the second "actual".
  // Selected keys whose request still exists (a WebSocket refresh can remove one),
  // so a stale selection can't produce an empty/invalid diff payload.
  const validCompareKeys = useMemo(
    () => compareKeys.filter((key) => allRequests.some((item) => item.key === key)),
    [compareKeys, allRequests],
  );

  const compareJson = useMemo(() => {
    const toRequestJson = (item: JsonListItem): string => {
      const request = (item.value['httpRequest'] as Record<string, unknown> | undefined) ?? item.value;
      return JSON.stringify(request, null, 2);
    };
    return validCompareKeys.map((key) => {
      const entry = allRequests.find((item) => item.key === key);
      return entry ? toRequestJson(entry) : '';
    });
  }, [validCompareKeys, allRequests]);

  const canDiff = validCompareKeys.length === 2;

  return (
    <Box
      sx={{
        flex: 1,
        display: 'flex',
        gap: 1,
        p: 1,
        overflow: 'hidden',
        minHeight: 0,
      }}
    >
      {/* Master list */}
      <Paper
        variant="outlined"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          width: selectedEntry && !compareMode ? '40%' : '100%',
          minWidth: 300,
          overflow: 'hidden',
          transition: 'width 0.2s ease',
        }}
      >
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            px: 1,
            py: 0.25,
            borderBottom: 1,
            borderColor: 'divider',
            flexShrink: 0,
          }}
        >
          <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.79rem' }}>
            Traffic
          </Typography>
          {allRequests.length > 0 && (
            <Chip
              label={allRequests.length > 999 ? '999+' : allRequests.length}
              color="primary"
              size="small"
              sx={{ height: 18, fontSize: '0.65rem', '& .MuiChip-label': { px: 0.75 } }}
            />
          )}
          <TextField
            id="traffic-inspector-search"
            size="small"
            placeholder="Search..."
            value={trafficSearch}
            onChange={(e) => setTrafficSearch(e.target.value)}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" />
                  </InputAdornment>
                ),
              },
            }}
            sx={{
              ml: 'auto',
              maxWidth: 200,
              '& .MuiInputBase-root': { height: 28, fontSize: '0.75rem' },
              '& .MuiSvgIcon-root': { fontSize: '0.875rem' },
            }}
          />
          <Tooltip title="Pick two requests to diff field-by-field">
            <ToggleButton
              value="compare"
              size="small"
              selected={compareMode}
              onChange={toggleCompareMode}
              aria-label="Compare requests"
              sx={{ height: 28, px: 1, fontSize: '0.7rem', textTransform: 'none', flexShrink: 0 }}
            >
              <CompareArrowsIcon sx={{ fontSize: '0.95rem', mr: 0.5 }} />
              Compare
            </ToggleButton>
          </Tooltip>
          {compareMode && (
            <Button
              size="small"
              variant="contained"
              disabled={!canDiff}
              onClick={() => setDiffDialogOpen(true)}
              sx={{ height: 28, px: 1, fontSize: '0.7rem', textTransform: 'none', flexShrink: 0 }}
            >
              Diff ({compareKeys.length}/2)
            </Button>
          )}
        </Box>
        <Box sx={{ flex: 1, overflowY: 'auto', bgcolor: 'background.default' }}>
          {filtered.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
              {allRequests.length === 0 ? 'No captured requests yet' : 'No matching requests'}
            </Typography>
          ) : (
            filtered.map(({ item, summary }, index) => (
              <TrafficRow
                key={item.key}
                summary={summary}
                index={filtered.length - index}
                selected={compareMode ? compareKeys.includes(item.key) : selectedKey === item.key}
                onClick={() =>
                  compareMode ? toggleCompareKey(item.key) : handleRowClick(item.key)
                }
                compareMode={compareMode}
                compareChecked={compareKeys.includes(item.key)}
                compareDisabled={compareKeys.length >= 2}
                onCompareToggle={() => toggleCompareKey(item.key)}
              />
            ))
          )}
        </Box>
      </Paper>

      {/* Detail pane (hidden while picking requests to compare) */}
      {selectedEntry && !compareMode && (
        <Paper
          variant="outlined"
          sx={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
            minWidth: 0,
          }}
        >
          <DetailPane
            key={selectedEntry.item.key}
            item={selectedEntry.item}
            summary={selectedEntry.summary}
            scriptedTurns={scriptedTurns}
            onCaptureAsMock={() => setCaptureDialogOpen(true)}
          />
        </Paper>
      )}

      {/* Capture as mock dialog */}
      {selectedEntry && isCapturableTraffic(selectedEntry.summary.parsed) && (
        <CaptureAsMockDialog
          open={captureDialogOpen}
          onClose={() => setCaptureDialogOpen(false)}
          parsed={selectedEntry.summary.parsed}
          path={selectedEntry.summary.path ?? ''}
          connectionParams={connectionParams}
          itemValue={selectedEntry.item.value}
        />
      )}

      {/* Diff two picked requests, reusing the shared dialog + PUT /mockserver/diff endpoint.
          Mount only while open and key on the selection so the dialog seeds fresh inputs each time. */}
      {diffDialogOpen && (
        <DiffRequestsDialog
          key={compareKeys.join('|')}
          open
          onClose={() => setDiffDialogOpen(false)}
          connectionParams={connectionParams}
          initialExpected={compareJson[0] ?? ''}
          initialActual={compareJson[1] ?? ''}
        />
      )}

    </Box>
  );
}
