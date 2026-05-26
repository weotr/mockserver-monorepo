import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import BuildIcon from '@mui/icons-material/Build';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import JsonViewer from './JsonViewer';
import type {
  AnthropicParsed,
  AnthropicContentBlock,
  OpenAiParsed,
  OpenAiChoice,
} from '../lib/llmTraffic';

// ---------------------------------------------------------------------------
// Shared bubble styling constants
// ---------------------------------------------------------------------------

const BUBBLE_MAX_WIDTH = '75%';
const BUBBLE_RADIUS = 2.5; // MUI spacing => 20px
const MONO_FONT_SIZE = '0.75rem';
const LABEL_FONT_SIZE = '0.65rem';

const leftBubbleSx = {
  alignSelf: 'flex-start',
  maxWidth: BUBBLE_MAX_WIDTH,
  bgcolor: 'action.hover',
  borderRadius: BUBBLE_RADIUS,
  px: 1.5,
  py: 1,
} as const;

const rightBubbleSx = {
  alignSelf: 'flex-end',
  maxWidth: BUBBLE_MAX_WIDTH,
  bgcolor: 'primary.main',
  color: 'primary.contrastText',
  borderRadius: BUBBLE_RADIUS,
  px: 1.5,
  py: 1,
} as const;

const toolBubbleSx = {
  maxWidth: BUBBLE_MAX_WIDTH,
  bgcolor: 'background.paper',
  border: 1,
  borderColor: 'secondary.main',
  borderRadius: BUBBLE_RADIUS,
  px: 1.5,
  py: 1,
} as const;

// ---------------------------------------------------------------------------
// Role label above a bubble
// ---------------------------------------------------------------------------

function RoleLabel({ role, align }: { role: string; align: 'left' | 'right' }) {
  return (
    <Typography
      variant="caption"
      sx={{
        display: 'block',
        fontSize: LABEL_FONT_SIZE,
        color: 'text.secondary',
        textAlign: align,
        mb: 0.25,
        textTransform: 'capitalize',
      }}
    >
      {role}
    </Typography>
  );
}

// ---------------------------------------------------------------------------
// System banner (full-width, centered, muted)
// ---------------------------------------------------------------------------

function SystemBanner({ content }: { content: unknown }) {
  return (
    <Box
      sx={{
        width: '100%',
        bgcolor: 'action.disabledBackground',
        borderRadius: 1.5,
        px: 2,
        py: 1,
        mb: 1.5,
      }}
    >
      <Typography
        variant="caption"
        sx={{
          display: 'block',
          fontWeight: 700,
          fontSize: LABEL_FONT_SIZE,
          color: 'text.secondary',
          mb: 0.5,
          textAlign: 'center',
          textTransform: 'uppercase',
          letterSpacing: 0.5,
        }}
      >
        System
      </Typography>
      {typeof content === 'string' ? (
        <Typography
          variant="body2"
          sx={{
            fontFamily: 'monospace',
            fontSize: MONO_FONT_SIZE,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {content}
        </Typography>
      ) : Array.isArray(content) ? (
        // Anthropic system can be an array of blocks
        content.map((block, i) => {
          const b = block as Record<string, unknown>;
          if (b['type'] === 'text' && typeof b['text'] === 'string') {
            return (
              <Typography
                key={i}
                variant="body2"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: MONO_FONT_SIZE,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {b['text']}
              </Typography>
            );
          }
          return (
            <Box key={i} sx={{ mt: 0.5 }}>
              <JsonViewer data={b} collapsed={2} />
            </Box>
          );
        })
      ) : (
        <JsonViewer data={content as Record<string, unknown>} collapsed={2} />
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Anthropic content block renderer (used for both request messages and response)
// ---------------------------------------------------------------------------

function renderAnthropicContentBlock(
  block: AnthropicContentBlock,
  index: number,
  side: 'left' | 'right',
) {
  if (block.type === 'text' && block.text != null) {
    return (
      <Typography
        key={index}
        variant="body2"
        sx={{
          fontFamily: 'monospace',
          fontSize: MONO_FONT_SIZE,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          color: side === 'right' ? 'primary.contrastText' : 'text.primary',
        }}
      >
        {block.text}
      </Typography>
    );
  }

  if (block.type === 'tool_use') {
    return (
      <Box key={index} sx={{ ...toolBubbleSx, alignSelf: side === 'left' ? 'flex-start' : 'flex-end', mt: 0.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 0.5 }}>
          <BuildIcon sx={{ fontSize: '0.85rem', color: 'secondary.main' }} />
          <Typography variant="caption" sx={{ fontFamily: 'monospace', fontWeight: 600, fontSize: LABEL_FONT_SIZE, color: 'secondary.main' }}>
            {block.name ?? 'tool_use'}
          </Typography>
        </Box>
        {block.input != null && (
          <JsonViewer data={block.input as Record<string, unknown>} collapsed={1} />
        )}
      </Box>
    );
  }

  if (block.type === 'tool_result') {
    const b = block as unknown as Record<string, unknown>;
    return (
      <Box key={index} sx={{ ...toolBubbleSx, alignSelf: 'flex-start', borderColor: 'info.main', mt: 0.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 0.5 }}>
          <BuildIcon sx={{ fontSize: '0.85rem', color: 'info.main' }} />
          <Typography variant="caption" sx={{ fontFamily: 'monospace', fontWeight: 600, fontSize: LABEL_FONT_SIZE, color: 'info.main' }}>
            tool_result
          </Typography>
        </Box>
        {typeof b['content'] === 'string' ? (
          <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: MONO_FONT_SIZE, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {b['content']}
          </Typography>
        ) : b['content'] != null ? (
          <JsonViewer data={b['content'] as Record<string, unknown>} collapsed={1} />
        ) : null}
      </Box>
    );
  }

  if (block.type === 'image') {
    return (
      <Typography key={index} variant="body2" sx={{ fontFamily: 'monospace', fontSize: MONO_FONT_SIZE, color: 'text.secondary' }}>
        [image]
      </Typography>
    );
  }

  // Unknown block type
  return (
    <Typography key={index} variant="body2" sx={{ fontFamily: 'monospace', fontSize: MONO_FONT_SIZE, color: 'text.secondary' }}>
      [{block.type}]
    </Typography>
  );
}

// ---------------------------------------------------------------------------
// Render Anthropic message content (string or array of blocks)
// ---------------------------------------------------------------------------

function renderAnthropicMessageContent(content: unknown, side: 'left' | 'right') {
  if (typeof content === 'string') {
    return (
      <Typography
        variant="body2"
        sx={{
          fontFamily: 'monospace',
          fontSize: MONO_FONT_SIZE,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          color: side === 'right' ? 'primary.contrastText' : 'text.primary',
        }}
      >
        {content}
      </Typography>
    );
  }

  if (Array.isArray(content)) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {content.map((block, i) => renderAnthropicContentBlock(block as AnthropicContentBlock, i, side))}
      </Box>
    );
  }

  // Fallback: render as JSON
  if (content != null && typeof content === 'object') {
    return <JsonViewer data={content as Record<string, unknown>} collapsed={2} />;
  }

  return null;
}

// ---------------------------------------------------------------------------
// Render OpenAI message content
// ---------------------------------------------------------------------------

function renderOpenAiToolCalls(toolCalls: unknown[], side: 'left' | 'right') {
  return toolCalls.map((tc, i) => {
    const call = tc as Record<string, unknown>;
    const fn = call['function'] as Record<string, unknown> | undefined;
    const name = fn ? String(fn['name'] ?? 'function') : 'tool_call';
    let args: unknown = fn?.['arguments'];
    if (typeof args === 'string') {
      try { args = JSON.parse(args); } catch { /* leave as string */ }
    }

    return (
      <Box key={i} sx={{ ...toolBubbleSx, alignSelf: side === 'left' ? 'flex-start' : 'flex-end', mt: 0.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 0.5 }}>
          <BuildIcon sx={{ fontSize: '0.85rem', color: 'secondary.main' }} />
          <Typography variant="caption" sx={{ fontFamily: 'monospace', fontWeight: 600, fontSize: LABEL_FONT_SIZE, color: 'secondary.main' }}>
            {name}
          </Typography>
        </Box>
        {args != null && (
          typeof args === 'object' ? (
            <JsonViewer data={args as Record<string, unknown>} collapsed={1} />
          ) : (
            <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: MONO_FONT_SIZE, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {String(args)}
            </Typography>
          )
        )}
      </Box>
    );
  });
}

// ---------------------------------------------------------------------------
// Metadata strip (chips across the top)
// ---------------------------------------------------------------------------

interface MetadataStripProps {
  model: string | null;
  inputTokens?: number;
  outputTokens?: number;
  stopReason: string | null;
  streamed: boolean;
  streamTruncated: boolean;
}

function MetadataStrip({ model, inputTokens, outputTokens, stopReason, streamed, streamTruncated }: MetadataStripProps) {
  const hasAny = model || inputTokens != null || outputTokens != null || stopReason || streamed || streamTruncated;
  if (!hasAny) return null;

  return (
    <Box sx={{ display: 'flex', gap: 0.75, flexWrap: 'wrap', mb: 1.5, justifyContent: 'center' }}>
      {model && <Chip label={model} size="small" variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />}
      {inputTokens != null && <Chip label={`In: ${inputTokens}`} size="small" variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />}
      {outputTokens != null && <Chip label={`Out: ${outputTokens}`} size="small" variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />}
      {stopReason && <Chip label={`Stop: ${stopReason}`} size="small" variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />}
      {streamed && <Chip label="Streamed" size="small" color="info" variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />}
      {streamTruncated && <Chip label="Truncated" size="small" color="warning" variant="outlined" sx={{ height: 20, fontSize: '0.65rem' }} />}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Anthropic ConversationView
// ---------------------------------------------------------------------------

export function AnthropicConversationView({ parsed }: { parsed: AnthropicParsed }) {
  const inputTokens = parsed.usage?.input_tokens;
  const outputTokens = parsed.usage?.output_tokens;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, p: 1 }}>
      <MetadataStrip
        model={parsed.model}
        inputTokens={inputTokens}
        outputTokens={outputTokens}
        stopReason={parsed.stopReason}
        streamed={parsed.streamed}
        streamTruncated={parsed.streamTruncated}
      />

      {/* System banner */}
      {parsed.system != null && <SystemBanner content={parsed.system} />}

      {/* Request messages */}
      {parsed.messages.map((msg, i) => {
        const m = msg as Record<string, unknown>;
        const role = String(m['role'] ?? 'unknown');
        const isLeft = role === 'user' || role === 'tool';
        const side = isLeft ? 'left' : 'right';
        const bubbleSx = isLeft ? leftBubbleSx : rightBubbleSx;

        return (
          <Box key={`msg-${i}`} sx={{ display: 'flex', flexDirection: 'column' }}>
            <RoleLabel role={role} align={side} />
            <Box sx={bubbleSx}>
              {renderAnthropicMessageContent(m['content'], side)}
            </Box>
          </Box>
        );
      })}

      {/* Response (final assistant bubble) */}
      {parsed.responseContent.length > 0 && (
        <Box sx={{ display: 'flex', flexDirection: 'column' }}>
          <RoleLabel role="assistant" align="right" />
          <Box sx={{ ...rightBubbleSx, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
            {parsed.responseContent.map((block, i) =>
              block.type === 'tool_use'
                ? renderAnthropicContentBlock(block, i, 'right')
                : renderAnthropicContentBlock(block, i, 'right'),
            )}
          </Box>
        </Box>
      )}

      {/* Empty state */}
      {parsed.messages.length === 0 && parsed.responseContent.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 2 }}>
          No conversation content
        </Typography>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// OpenAI ConversationView
// ---------------------------------------------------------------------------

export function OpenAiConversationView({ parsed }: { parsed: OpenAiParsed }) {
  const inputTokens = parsed.usage?.prompt_tokens;
  const outputTokens = parsed.usage?.completion_tokens;

  // Compute the first finish_reason from choices
  const finishReason = parsed.choices.reduce<string | null>((acc, c) => acc ?? c.finish_reason ?? null, null);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, p: 1 }}>
      <MetadataStrip
        model={parsed.model}
        inputTokens={inputTokens}
        outputTokens={outputTokens}
        stopReason={finishReason}
        streamed={parsed.streamed}
        streamTruncated={parsed.streamTruncated}
      />

      {/* Request messages */}
      {parsed.messages.map((msg, i) => {
        const m = msg as Record<string, unknown>;
        const role = String(m['role'] ?? 'unknown');

        // System message => banner
        if (role === 'system') {
          return <SystemBanner key={`msg-${i}`} content={m['content']} />;
        }

        const isLeft = role === 'user' || role === 'tool';
        const side = isLeft ? 'left' : 'right';
        const bubbleSx = isLeft ? leftBubbleSx : rightBubbleSx;

        const content = m['content'];
        const toolCalls = m['tool_calls'] as unknown[] | undefined;

        return (
          <Box key={`msg-${i}`} sx={{ display: 'flex', flexDirection: 'column' }}>
            <RoleLabel role={role} align={side} />
            {content != null && (
              <Box sx={bubbleSx}>
                {typeof content === 'string' ? (
                  <Typography
                    variant="body2"
                    sx={{
                      fontFamily: 'monospace',
                      fontSize: MONO_FONT_SIZE,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                      color: side === 'right' ? 'primary.contrastText' : 'text.primary',
                    }}
                  >
                    {content}
                  </Typography>
                ) : Array.isArray(content) ? (
                  // OpenAI content can be an array of parts (text, image_url, etc.)
                  content.map((part, j) => {
                    const p = part as Record<string, unknown>;
                    if (p['type'] === 'text' && typeof p['text'] === 'string') {
                      return (
                        <Typography
                          key={j}
                          variant="body2"
                          sx={{
                            fontFamily: 'monospace',
                            fontSize: MONO_FONT_SIZE,
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-word',
                            color: side === 'right' ? 'primary.contrastText' : 'text.primary',
                          }}
                        >
                          {p['text']}
                        </Typography>
                      );
                    }
                    if (p['type'] === 'image_url') {
                      return (
                        <Typography key={j} variant="body2" sx={{ fontFamily: 'monospace', fontSize: MONO_FONT_SIZE, color: 'text.secondary' }}>
                          [image]
                        </Typography>
                      );
                    }
                    return (
                      <Typography key={j} variant="body2" sx={{ fontFamily: 'monospace', fontSize: MONO_FONT_SIZE, color: 'text.secondary' }}>
                        [{String(p['type'] ?? 'unknown')}]
                      </Typography>
                    );
                  })
                ) : (
                  <JsonViewer data={content as Record<string, unknown>} collapsed={2} />
                )}
              </Box>
            )}
            {/* Tool calls attached to this message */}
            {toolCalls && toolCalls.length > 0 && renderOpenAiToolCalls(toolCalls, side)}
          </Box>
        );
      })}

      {/* Response choices */}
      {parsed.choices.map((choice: OpenAiChoice, i: number) => {
        const hasContent = choice.message?.content != null;
        const hasToolCalls = choice.message?.tool_calls && choice.message.tool_calls.length > 0;

        if (!hasContent && !hasToolCalls) return null;

        return (
          <Box key={`choice-${i}`} sx={{ display: 'flex', flexDirection: 'column' }}>
            <RoleLabel role="assistant" align="right" />
            {hasContent && (
              <Box sx={rightBubbleSx}>
                <Typography
                  variant="body2"
                  sx={{
                    fontFamily: 'monospace',
                    fontSize: MONO_FONT_SIZE,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    color: 'primary.contrastText',
                  }}
                >
                  {choice.message!.content}
                </Typography>
              </Box>
            )}
            {hasToolCalls && renderOpenAiToolCalls(choice.message!.tool_calls!, 'right')}
          </Box>
        );
      })}

      {/* Empty state */}
      {parsed.messages.length === 0 && parsed.choices.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 2 }}>
          No conversation content
        </Typography>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Scripted turn data structures (from httpLlmResponse.conversationPredicates)
// ---------------------------------------------------------------------------

export interface ScriptedTurn {
  turnIndex: number;
  predicates: Record<string, unknown>;
  response: {
    text?: string;
    toolCalls?: Array<{ name: string; arguments?: string }>;
    stopReason?: string;
    streaming?: boolean;
  };
  scenarioState: string;
  newScenarioState: string;
}

// ---------------------------------------------------------------------------
// ScriptedTurnsPanel — renders the scripted turn sequence
// ---------------------------------------------------------------------------

export function ScriptedTurnsPanel({ turns }: { turns: ScriptedTurn[] }) {
  if (turns.length === 0) return null;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, p: 1 }}>
      <Typography
        variant="subtitle2"
        sx={{
          fontWeight: 700,
          textTransform: 'uppercase',
          letterSpacing: 0.5,
          fontSize: '0.7rem',
          color: 'text.secondary',
          textAlign: 'center',
          mb: 0.5,
        }}
      >
        Scripted Conversation Turns
      </Typography>

      {turns.map((turn, i) => {
        const predicateEntries = Object.entries(turn.predicates).filter(
          ([, v]) => v !== undefined && v !== null,
        );
        const hasPredicates = predicateEntries.length > 0;

        return (
          <Box
            key={`scripted-turn-${i}`}
            sx={{
              border: 1,
              borderColor: 'divider',
              borderRadius: 2,
              p: 1.5,
            }}
          >
            {/* Turn header with state transition */}
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 1 }}>
              <Chip
                label={`Turn ${turn.turnIndex}`}
                size="small"
                color="primary"
                variant="outlined"
                sx={{ height: 20, fontSize: '0.65rem', fontWeight: 600 }}
              />
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                <Chip
                  label={turn.scenarioState}
                  size="small"
                  variant="outlined"
                  sx={{ height: 18, fontSize: '0.6rem' }}
                />
                <ArrowForwardIcon sx={{ fontSize: '0.75rem', color: 'text.secondary' }} />
                <Chip
                  label={turn.newScenarioState}
                  size="small"
                  variant="outlined"
                  sx={{ height: 18, fontSize: '0.6rem' }}
                />
              </Box>
            </Box>

            {/* Predicates */}
            {hasPredicates && (
              <Box sx={{ mb: 1 }}>
                <Typography
                  variant="caption"
                  sx={{ fontSize: '0.6rem', color: 'text.secondary', fontWeight: 600, textTransform: 'uppercase' }}
                >
                  When
                </Typography>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mt: 0.25 }}>
                  {predicateEntries.map(([key, value]) => (
                    <Chip
                      key={key}
                      label={`${key}: ${String(value)}`}
                      size="small"
                      variant="outlined"
                      color="info"
                      sx={{ height: 18, fontSize: '0.6rem' }}
                    />
                  ))}
                </Box>
              </Box>
            )}

            <Divider sx={{ my: 0.5 }} />

            {/* Response */}
            <Box>
              <Typography
                variant="caption"
                sx={{ fontSize: '0.6rem', color: 'text.secondary', fontWeight: 600, textTransform: 'uppercase' }}
              >
                Response
              </Typography>
              {turn.response.text && (
                <Typography
                  variant="body2"
                  sx={{
                    fontFamily: 'monospace',
                    fontSize: MONO_FONT_SIZE,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    mt: 0.25,
                  }}
                >
                  {turn.response.text}
                </Typography>
              )}
              {turn.response.toolCalls && turn.response.toolCalls.length > 0 && (
                <Box sx={{ mt: 0.5, display: 'flex', flexDirection: 'column', gap: 0.25 }}>
                  {turn.response.toolCalls.map((tc, j) => (
                    <Box key={j} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <BuildIcon sx={{ fontSize: '0.75rem', color: 'secondary.main' }} />
                      <Typography variant="caption" sx={{ fontFamily: 'monospace', fontSize: '0.65rem', fontWeight: 600, color: 'secondary.main' }}>
                        {tc.name}
                      </Typography>
                      {tc.arguments && (
                        <Typography variant="caption" sx={{ fontFamily: 'monospace', fontSize: '0.6rem', color: 'text.secondary' }}>
                          ({tc.arguments.length > 40 ? tc.arguments.substring(0, 40) + '...' : tc.arguments})
                        </Typography>
                      )}
                    </Box>
                  ))}
                </Box>
              )}
              {turn.response.stopReason && (
                <Chip
                  label={`Stop: ${turn.response.stopReason}`}
                  size="small"
                  variant="outlined"
                  sx={{ height: 18, fontSize: '0.6rem', mt: 0.5 }}
                />
              )}
              {turn.response.streaming && (
                <Chip
                  label="Streamed"
                  size="small"
                  color="info"
                  variant="outlined"
                  sx={{ height: 18, fontSize: '0.6rem', mt: 0.5, ml: 0.5 }}
                />
              )}
            </Box>
          </Box>
        );
      })}
    </Box>
  );
}
