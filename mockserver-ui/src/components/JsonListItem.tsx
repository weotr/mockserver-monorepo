import { useState, useMemo } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import type { JsonListItem as JsonListItemType } from '../types';
import JsonViewer from './JsonViewer';
import DescriptionDisplay from './DescriptionDisplay';

interface JsonListItemProps {
  item: JsonListItemType;
  index: number;
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
  if (completion) {
    const text = completion['text'] as string | undefined;
    if (text) {
      textPreview = text.length > 80 ? text.substring(0, 80) + '…' : text;
    }
  }

  return { provider, model, textPreview };
}

export default function JsonListItem({ item, index }: JsonListItemProps) {
  const [expanded, setExpanded] = useState(false);
  const llmBadge = useMemo(() => extractLlmBadge(item.value), [item.value]);

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
          alignItems: 'center',
          gap: 0.5,
          cursor: 'pointer',
          userSelect: 'none',
        }}
        onClick={() => setExpanded((prev) => !prev)}
      >
        <IconButton size="small" sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: '1rem' } }}>
          {expanded ? <ExpandMoreIcon /> : <ChevronRightIcon />}
        </IconButton>
        <Box
          component="span"
          sx={{ fontFamily: 'monospace', fontSize: '0.8em', color: 'text.secondary', minWidth: 24 }}
        >
          {index}
        </Box>
        {item.description && <DescriptionDisplay description={item.description} />}
        {llmBadge && (
          <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, ml: 0.5 }}>
            <Chip
              icon={<SmartToyIcon sx={{ fontSize: '0.85rem' }} />}
              label={`LLM Response – ${llmBadge.provider}${llmBadge.model ? ' / ' + llmBadge.model : ''}`}
              size="small"
              color="secondary"
              variant="outlined"
              sx={{ height: 20, fontSize: '0.65rem' }}
            />
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
        )}
      </Box>
      {expanded && (
        <Box sx={{ pl: 3.5, pt: 0.5 }}>
          <JsonViewer data={item.value} collapsed={1} enableClipboard={true} />
        </Box>
      )}
    </Box>
  );
}
