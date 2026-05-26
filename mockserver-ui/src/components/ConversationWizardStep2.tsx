import { useCallback } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Typography from '@mui/material/Typography';
import IconButton from '@mui/material/IconButton';
import FormControlLabel from '@mui/material/FormControlLabel';
import Switch from '@mui/material/Switch';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import PredicatePills from './PredicatePills';
import type { TurnDraft, TurnMatchPredicates, TurnResponse } from '../lib/conversationCodegen';
import type { ToolCallDraft } from '../lib/expectationFromCapture';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface Step2Props {
  turns: TurnDraft[];
  onTurnsChange: (turns: TurnDraft[]) => void;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const ROLES = ['USER', 'ASSISTANT', 'TOOL', 'SYSTEM'] as const;

function emptyTurn(): TurnDraft {
  return {
    predicates: {},
    response: { text: '', toolCalls: [], stopReason: '', streaming: false },
  };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ConversationWizardStep2({ turns, onTurnsChange }: Step2Props) {
  const updateTurn = useCallback(
    (index: number, partial: Partial<TurnDraft>) => {
      onTurnsChange(turns.map((t, i) => (i === index ? { ...t, ...partial } : t)));
    },
    [turns, onTurnsChange],
  );

  const updatePredicates = useCallback(
    (index: number, partial: Partial<TurnMatchPredicates>) => {
      const turn = turns[index]!;
      updateTurn(index, { predicates: { ...turn.predicates, ...partial } });
    },
    [turns, updateTurn],
  );

  const updateResponse = useCallback(
    (index: number, partial: Partial<TurnResponse>) => {
      const turn = turns[index]!;
      updateTurn(index, { response: { ...turn.response, ...partial } });
    },
    [turns, updateTurn],
  );

  const updateToolCall = useCallback(
    (turnIndex: number, tcIndex: number, partial: Partial<ToolCallDraft>) => {
      const turn = turns[turnIndex]!;
      const newToolCalls = turn.response.toolCalls.map((tc, i) =>
        i === tcIndex ? { ...tc, ...partial } : tc,
      );
      updateResponse(turnIndex, { toolCalls: newToolCalls });
    },
    [turns, updateResponse],
  );

  const addToolCall = useCallback(
    (turnIndex: number) => {
      const turn = turns[turnIndex]!;
      updateResponse(turnIndex, {
        toolCalls: [...turn.response.toolCalls, { name: '', arguments: '' }],
      });
    },
    [turns, updateResponse],
  );

  const removeToolCall = useCallback(
    (turnIndex: number, tcIndex: number) => {
      const turn = turns[turnIndex]!;
      updateResponse(turnIndex, {
        toolCalls: turn.response.toolCalls.filter((_, i) => i !== tcIndex),
      });
    },
    [turns, updateResponse],
  );

  const addTurn = useCallback(() => {
    onTurnsChange([...turns, emptyTurn()]);
  }, [turns, onTurnsChange]);

  const removeTurn = useCallback(
    (index: number) => {
      if (turns.length <= 1) return; // Keep at least one
      onTurnsChange(turns.filter((_, i) => i !== index));
    },
    [turns, onTurnsChange],
  );

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, py: 1 }}>
      {turns.map((turn, i) => (
        <Card key={i} variant="outlined">
          <CardContent sx={{ pb: '12px !important' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
              <Typography variant="subtitle2">Turn {i}</Typography>
              <IconButton
                size="small"
                onClick={() => removeTurn(i)}
                disabled={turns.length <= 1}
                aria-label={`Remove turn ${i}`}
              >
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>

            {/* Predicates */}
            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, display: 'block', mb: 0.5 }}>
              Match predicates
            </Typography>

            {/* Preview pills */}
            <PredicatePills predicates={turn.predicates} />

            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mt: 1, mb: 1.5 }}>
              <TextField
                label="Turn index"
                size="small"
                type="number"
                value={turn.predicates.turnIndex ?? ''}
                onChange={(e) => {
                  const v = e.target.value;
                  updatePredicates(i, {
                    turnIndex: v === '' ? undefined : parseInt(v, 10),
                  });
                }}
                sx={{ width: 110 }}
              />
              <TextField
                label="Latest msg contains"
                size="small"
                value={turn.predicates.latestMessageContains ?? ''}
                onChange={(e) =>
                  updatePredicates(i, {
                    latestMessageContains: e.target.value || undefined,
                  })
                }
                sx={{ flex: 1, minWidth: 160 }}
              />
              <TextField
                label="Latest msg regex"
                size="small"
                value={turn.predicates.latestMessageMatches ?? ''}
                onChange={(e) =>
                  updatePredicates(i, {
                    latestMessageMatches: e.target.value || undefined,
                  })
                }
                sx={{ flex: 1, minWidth: 160 }}
              />
              <TextField
                label="Latest msg role"
                size="small"
                select
                value={turn.predicates.latestMessageRole ?? ''}
                onChange={(e) =>
                  updatePredicates(i, {
                    latestMessageRole:
                      (e.target.value as TurnMatchPredicates['latestMessageRole']) || undefined,
                  })
                }
                sx={{ width: 130 }}
              >
                <MenuItem value="">None</MenuItem>
                {ROLES.map((r) => (
                  <MenuItem key={r} value={r}>
                    {r}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Contains tool result for"
                size="small"
                value={turn.predicates.containsToolResultFor ?? ''}
                onChange={(e) =>
                  updatePredicates(i, {
                    containsToolResultFor: e.target.value || undefined,
                  })
                }
                sx={{ flex: 1, minWidth: 160 }}
              />
            </Box>

            {/* Response */}
            <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, display: 'block', mb: 0.5 }}>
              Response
            </Typography>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              <TextField
                label="Text"
                size="small"
                fullWidth
                multiline
                minRows={2}
                maxRows={6}
                value={turn.response.text}
                onChange={(e) => updateResponse(i, { text: e.target.value })}
              />

              {/* Tool calls */}
              <Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                  <Typography variant="caption" sx={{ fontWeight: 600 }}>
                    Tool Calls
                  </Typography>
                  <IconButton size="small" onClick={() => addToolCall(i)} aria-label="Add tool call">
                    <AddIcon sx={{ fontSize: '0.875rem' }} />
                  </IconButton>
                </Box>
                {turn.response.toolCalls.map((tc, j) => (
                  <Box key={j} sx={{ display: 'flex', gap: 1, mb: 0.5, alignItems: 'flex-start' }}>
                    <TextField
                      label="Name"
                      size="small"
                      value={tc.name}
                      onChange={(e) => updateToolCall(i, j, { name: e.target.value })}
                      sx={{ flex: 1 }}
                    />
                    <TextField
                      label="Arguments"
                      size="small"
                      value={tc.arguments ?? ''}
                      onChange={(e) => updateToolCall(i, j, { arguments: e.target.value })}
                      sx={{ flex: 2 }}
                    />
                    <IconButton
                      size="small"
                      onClick={() => removeToolCall(i, j)}
                      aria-label="Remove tool call"
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Box>
                ))}
              </Box>

              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                <TextField
                  label="Stop reason"
                  size="small"
                  value={turn.response.stopReason}
                  onChange={(e) => updateResponse(i, { stopReason: e.target.value })}
                  sx={{ flex: 1 }}
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={turn.response.streaming}
                      onChange={(e) => updateResponse(i, { streaming: e.target.checked })}
                      size="small"
                    />
                  }
                  label="Streaming"
                  sx={{ '& .MuiFormControlLabel-label': { fontSize: '0.75rem' } }}
                />
              </Box>
            </Box>
          </CardContent>
        </Card>
      ))}

      <Button
        variant="outlined"
        size="small"
        startIcon={<AddIcon />}
        onClick={addTurn}
        sx={{ alignSelf: 'flex-start', textTransform: 'none' }}
      >
        Add turn
      </Button>
    </Box>
  );
}
