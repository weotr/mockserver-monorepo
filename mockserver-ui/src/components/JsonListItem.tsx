import { Fragment, useState, useMemo } from 'react';
import type React from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import IconButton from '@mui/material/IconButton';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import BoltIcon from '@mui/icons-material/Bolt';
import type { JsonListItem as JsonListItemType } from '../types';
import type { ConversationPredicates } from '../lib/llmTraffic';
import JsonViewer from './JsonViewer';
import DescriptionDisplay from './DescriptionDisplay';
import PredicatePills from './PredicatePills';

interface JsonListItemProps {
  item: JsonListItemType;
  index: number;
  /**
   * Optional turn-position info for LLM scenario expectations. When present a
   * `[Turn N of M]` chip is rendered alongside the predicate pills so the user
   * sees the canonical sequence even when the turn lacks an explicit
   * `turnIndex` predicate (e.g. matches via `containsToolResultFor`).
   */
  turnPosition?: { position: number; total: number };
}

const PROVIDER_LABELS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  OPENAI_RESPONSES: 'OpenAI Responses',
  GEMINI: 'Gemini',
  BEDROCK: 'Bedrock',
  AZURE_OPENAI: 'Azure OpenAI',
  OLLAMA: 'Ollama',
};

interface LlmBadgeInfo {
  provider: string;
  model: string | null;
  textPreview: string | null;
  streaming: boolean;
  toolCallCount: number;
  isEmbedding: boolean;
  isStateful: boolean;
  turnIndex: number | null;
  isolationLabel: string | null;
  predicates: ConversationPredicates | null;
}

/**
 * Pull the request method + path out of the item value. Works for all three
 * panels: Active Expectations (httpRequest is the matcher), Received Requests
 * (the bare request was wrapped under httpRequest by DashboardWebSocketHandler),
 * and Proxied Requests (same wrapping). Returns null when the value has no
 * recognisable request shape (e.g. raw OpenAPI matchers) so the caller can
 * fall back to the opaque DescriptionDisplay.
 */
function extractRequestParts(value: Record<string, unknown>): { method: string | null; path: string | null } | null {
  const req = value['httpRequest'];
  if (!req || typeof req !== 'object') return null;
  const r = req as Record<string, unknown>;
  // method/path can be plain strings OR NottableString objects { value, not }
  const readField = (field: string): string | null => {
    const raw = r[field];
    if (typeof raw === 'string') return raw;
    if (raw && typeof raw === 'object' && 'value' in (raw as Record<string, unknown>)) {
      const v = (raw as Record<string, unknown>)['value'];
      return typeof v === 'string' ? v : null;
    }
    return null;
  };
  const method = readField('method');
  const path = readField('path');
  if (!method && !path) return null;
  return { method, path };
}

function extractIsolationLabel(scenarioName: string | undefined): string | null {
  if (!scenarioName) return null;
  const isoMatch = /__iso=([^_]+):(.+)$/.exec(scenarioName);
  if (!isoMatch) return null;
  return `${isoMatch[1]}:${isoMatch[2]}`;
}

function extractLlmBadge(value: Record<string, unknown>): LlmBadgeInfo | null {
  const llm = value['httpLlmResponse'] as Record<string, unknown> | undefined;
  if (!llm) return null;

  const providerRaw = llm['provider'] as string | undefined;
  if (!providerRaw) return null;

  const provider = PROVIDER_LABELS[providerRaw] ?? providerRaw;
  const model = (llm['model'] as string | undefined) ?? null;

  let textPreview: string | null = null;
  const completion = llm['completion'] as Record<string, unknown> | undefined;
  const streaming = (llm['streaming'] as boolean | undefined) === true;
  let toolCallCount = 0;
  if (completion) {
    const text = completion['text'] as string | undefined;
    if (text) {
      textPreview = text.length > 80 ? text.substring(0, 80) + '…' : text;
    }
    const toolCalls = completion['toolCalls'] as unknown[] | undefined;
    if (toolCalls) {
      toolCallCount = toolCalls.length;
    }
  }

  const isEmbedding = 'embedding' in llm;

  const predicatesObj = llm['conversationPredicates'] as ConversationPredicates | undefined;
  const isStateful = predicatesObj != null;
  const turnIndex = predicatesObj?.turnIndex ?? null;

  // We are already past the `if (!llm) return null` guard, so the scenario name
  // can only be read from the LLM action itself. The httpResponseObjectCallback
  // path the badge previously consulted was unreachable.
  const scenarioName = llm['scenarioName'] as string | undefined;
  const isolationLabel = extractIsolationLabel(scenarioName);

  return {
    provider,
    model,
    textPreview,
    streaming,
    toolCallCount,
    isEmbedding,
    isStateful,
    turnIndex,
    isolationLabel,
    predicates: predicatesObj ?? null,
  };
}

/**
 * Build a concise human-readable summary of a top-level `chaos` block on an
 * expectation. Returns null when no chaos profile is present.
 */
function extractChaosSummary(value: Record<string, unknown>): string | null {
  const raw = value['chaos'];
  if (!raw || typeof raw !== 'object') return null;
  const c = raw as Record<string, unknown>;
  const parts: string[] = [];
  if (typeof c['errorStatus'] === 'number') {
    const prob = typeof c['errorProbability'] === 'number' ? c['errorProbability'] as number : null;
    const probStr = prob != null ? ` @${Math.round(prob * 100)}%` : '';
    parts.push(`${c['errorStatus']}${probStr}`);
  }
  if (c['latency'] && typeof c['latency'] === 'object') {
    const lat = c['latency'] as Record<string, unknown>;
    if (typeof lat['value'] === 'number') {
      const unit = typeof lat['timeUnit'] === 'string' ? (lat['timeUnit'] as string).toLowerCase() : 'ms';
      parts.push(`${lat['value']}${unit.slice(0, 2)}`);
    }
  }
  if (typeof c['succeedFirst'] === 'number' || typeof c['failRequestCount'] === 'number') {
    const sf = typeof c['succeedFirst'] === 'number' ? c['succeedFirst'] as number : 0;
    const fc = typeof c['failRequestCount'] === 'number' ? `${c['failRequestCount']}` : '∞';
    parts.push(`window ${sf}+${fc}`);
  }
  return parts.length > 0 ? parts.join(', ') : 'enabled';
}

export default function JsonListItem({ item, index, turnPosition }: JsonListItemProps) {
  const [expanded, setExpanded] = useState(false);
  const llmBadge = useMemo(() => extractLlmBadge(item.value), [item.value]);
  const requestParts = useMemo(() => extractRequestParts(item.value), [item.value]);
  const chaosSummary = useMemo(() => extractChaosSummary(item.value), [item.value]);

  return (
    <Box
      sx={{
        position: 'relative',
        py: 0.5,
        px: 1,
        borderBottom: 1,
        borderColor: 'divider',
        '&:hover .copy-btn': { opacity: 1 },
        '&:last-child': { borderBottom: 0 },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          gap: 0.25,
          cursor: 'pointer',
          userSelect: 'none',
        }}
        onClick={() => setExpanded((prev) => !prev)}
      >
        {/* First row: expand toggle + position + (optional) expectation id +
            method on the left, path on the right. The expectation id is only
            present for Active Expectations entries (Received / Proxied request
            items have synthetic keys like "<id>_request" / "<id>_proxied" and
            no value.id field). */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 0 }}>
          <IconButton size="small" sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: '1rem' } }}>
            {expanded ? <ExpandMoreIcon /> : <ChevronRightIcon />}
          </IconButton>
          <Box
            component="span"
            sx={{ fontFamily: 'monospace', fontSize: '0.8em', color: 'text.secondary', minWidth: 24 }}
          >
            {index}
          </Box>
          {typeof item.value['id'] === 'string' && (
            <Box
              component="span"
              sx={{
                fontFamily: 'monospace',
                fontSize: '0.85em',
                fontWeight: 600,
                whiteSpace: 'nowrap',
              }}
            >
              {item.value['id'] as string}:
            </Box>
          )}
          {requestParts ? (
            <>
              <Box
                component="span"
                sx={{ fontFamily: 'monospace', fontSize: '0.85em', fontWeight: 600, minWidth: 50 }}
              >
                {requestParts.method ?? '·'}
              </Box>
              <Box sx={{ flex: 1, minWidth: 0 }} />
              <Box
                component="span"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.85em',
                  color: 'text.secondary',
                  textAlign: 'right',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  maxWidth: '70%',
                }}
                title={requestParts.path ?? undefined}
              >
                {requestParts.path ?? '·'}
              </Box>
            </>
          ) : (
            item.description && <DescriptionDisplay description={item.description} />
          )}
        </Box>
        {/* Second row: LLM badge chips below the matcher so wide chip sets wrap
            cleanly inside the panel instead of forcing horizontal scroll. */}
        {llmBadge && (() => {
          // Build optional signal chips (provider chip is mandatory and renders separately).
          const optionalChips: Array<{ key: string; element: React.ReactElement; tooltipLabel: string }> = [];
          if (llmBadge.streaming) {
            optionalChips.push({
              key: 'stream',
              tooltipLabel: 'streaming',
              element: (
                <Chip
                  icon={<BoltIcon sx={{ fontSize: '0.8rem' }} />}
                  label="stream"
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.toolCallCount > 0) {
            const lbl = `${llmBadge.toolCallCount} tool${llmBadge.toolCallCount > 1 ? 's' : ''}`;
            optionalChips.push({
              key: 'tools',
              tooltipLabel: lbl,
              element: (
                <Chip
                  label={lbl}
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.isEmbedding) {
            optionalChips.push({
              key: 'embedding',
              tooltipLabel: 'embedding',
              element: (
                <Chip
                  label="embedding"
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.isStateful) {
            // Prefer the canonical "Turn N of M" position computed from
            // scenario state ordering over the predicate-derived turnIndex,
            // since the latter is absent on turns that match via other
            // predicates (e.g. containsToolResultFor).
            const lbl = turnPosition
              ? `turn ${turnPosition.position} of ${turnPosition.total}`
              : llmBadge.turnIndex != null
                ? `turn ${llmBadge.turnIndex}`
                : 'stateful';
            optionalChips.push({
              key: 'stateful',
              tooltipLabel: lbl,
              element: (
                <Chip
                  label={lbl}
                  size="small"
                  color="info"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.isolationLabel) {
            const lbl = `iso=${llmBadge.isolationLabel}`;
            optionalChips.push({
              key: 'isolation',
              tooltipLabel: lbl,
              element: (
                <Chip
                  label={lbl}
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }

          // Cap at 3 visible optional chips (4 total including the mandatory provider chip).
          // Surplus is collapsed into a "+N more" chip with a tooltip listing them.
          const MAX_OPTIONAL = 3;
          const visibleChips = optionalChips.slice(0, MAX_OPTIONAL);
          const hiddenChips = optionalChips.slice(MAX_OPTIONAL);

          return (
          // pl matches the cumulative width of [expand icon] + gap + [index]
          // + gap so the chips visually start at the same x-coordinate as the
          // expectation id (when present) or the method (when not).
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, pl: 6, flexWrap: 'wrap' }}>
            <Chip
              icon={<SmartToyIcon sx={{ fontSize: '0.85rem' }} />}
              label={`LLM Response – ${llmBadge.provider}${llmBadge.model ? ' / ' + llmBadge.model : ''}`}
              size="small"
              color="secondary"
              variant="outlined"
              sx={{ height: 20, fontSize: '0.65rem' }}
            />
            {visibleChips.map((c) => <Fragment key={c.key}>{c.element}</Fragment>)}
            {hiddenChips.length > 0 && (
              <Tooltip title={hiddenChips.map((c) => c.tooltipLabel).join(', ')}>
                <Chip
                  label={`+${hiddenChips.length} more`}
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              </Tooltip>
            )}
            {llmBadge.textPreview && (
              <Box
                component="span"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.7em',
                  color: 'text.secondary',
                  maxWidth: 200,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {llmBadge.textPreview}
              </Box>
            )}
          </Box>
          );
        })()}
        {/* Chaos profile summary chip — shown for any expectation (standard
            or LLM) that has a top-level `chaos` block. */}
        {chaosSummary && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, pl: 6 }}>
            <Chip
              label={`Chaos: ${chaosSummary}`}
              size="small"
              color="warning"
              variant="outlined"
              sx={{ height: 20, fontSize: '0.65rem' }}
            />
          </Box>
        )}
      </Box>
      {expanded && (
        // pl: 6 matches the second-row chip indent so the expanded JSON
        // body lines up with the expectation id (Active Expectations) or
        // the method (Received / Proxied requests, where no id is shown).
        <Box sx={{ pl: 6, pt: 0.5 }}>
          {llmBadge?.predicates && (
            <Box sx={{ mb: 1 }}>
              <PredicatePills predicates={llmBadge.predicates} />
            </Box>
          )}
          <JsonViewer data={item.value} collapsed={1} enableClipboard={true} />
        </Box>
      )}
    </Box>
  );
}
