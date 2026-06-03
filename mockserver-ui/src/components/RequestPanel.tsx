import { useMemo } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import type { JsonListItem } from '../types';
import Panel from './Panel';
import JsonListItemComponent from './JsonListItem';

interface RequestPanelProps {
  title: string;
  items: JsonListItem[];
  searchValue: string;
  onSearchChange: (value: string) => void;
}

function matchesSearch(item: JsonListItem, term: string): boolean {
  return JSON.stringify(item).toLowerCase().includes(term.toLowerCase());
}

// ---------------------------------------------------------------------------
// W3C traceparent extraction (F8)
// ---------------------------------------------------------------------------

interface TraceparentInfo {
  raw: string;
  traceId: string;
  parentId: string;
  flags: string;
}

const TRACEPARENT_REGEX = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i;

function findTraceparentInHeaders(headers: unknown): TraceparentInfo | null {
  if (!headers) return null;

  const tryParse = (val: string): TraceparentInfo | null => {
    const match = TRACEPARENT_REGEX.exec(val.trim());
    if (!match) return null;
    return { raw: val.trim(), traceId: match[2]!, parentId: match[3]!, flags: match[4]! };
  };

  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (typeof h !== 'object' || h === null) continue;
      const entry = h as Record<string, unknown>;
      const name = entry['name'];
      if (typeof name === 'string' && name.toLowerCase() === 'traceparent') {
        const values = entry['values'];
        if (Array.isArray(values) && values.length > 0 && typeof values[0] === 'string') {
          return tryParse(values[0]);
        }
      }
    }
  } else if (typeof headers === 'object' && headers !== null) {
    const map = headers as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === 'traceparent') {
        const val = map[key];
        if (typeof val === 'string') return tryParse(val);
        if (Array.isArray(val) && val.length > 0 && typeof val[0] === 'string') return tryParse(val[0]);
      }
    }
  }
  return null;
}

function extractTraceparentFromItem(item: JsonListItem): TraceparentInfo | null {
  const value = item.value;
  // Check top-level headers
  const direct = findTraceparentInHeaders(value['headers']);
  if (direct) return direct;
  // Check nested httpRequest / httpResponse
  for (const key of ['httpRequest', 'httpResponse']) {
    const nested = value[key];
    if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
      const found = findTraceparentInHeaders((nested as Record<string, unknown>)['headers']);
      if (found) return found;
    }
  }
  return null;
}

function TraceparentPill({ info }: { info: TraceparentInfo }) {
  const abbrev = info.traceId.substring(0, 8);
  const tooltipText = [
    `traceparent: ${info.raw}`,
    `traceId: ${info.traceId}`,
    `parentId: ${info.parentId}`,
    `flags: ${info.flags} (${info.flags === '01' ? 'sampled' : 'not sampled'})`,
  ].join('\n');

  return (
    <Tooltip
      title={<Box component="pre" sx={{ m: 0, fontFamily: 'monospace', fontSize: '0.7rem', whiteSpace: 'pre-wrap' }}>{tooltipText}</Box>}
    >
      <Chip
        label={`[T] ${abbrev}`}
        size="small"
        color="info"
        variant="outlined"
        sx={{
          height: 18,
          fontSize: '0.6rem',
          fontFamily: 'monospace',
          '& .MuiChip-label': { px: 0.5 },
        }}
      />
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------

export default function RequestPanel({
  title,
  items,
  searchValue,
  onSearchChange,
}: RequestPanelProps) {
  const filtered = useMemo(
    () => (searchValue ? items.filter((e) => matchesSearch(e, searchValue)) : items),
    [items, searchValue],
  );

  return (
    <Panel
      title={title}
      count={items.length}
      searchValue={searchValue}
      onSearchChange={onSearchChange}
    >
      {filtered.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
          {items.length === 0 ? 'No requests yet — recorded requests appear here as traffic reaches the server.' : 'No matching requests'}
        </Typography>
      ) : (
        filtered.map((item, index) => {
          const tp = extractTraceparentFromItem(item);
          return (
            <Box key={item.key}>
              <JsonListItemComponent item={item} index={items.length - index} />
              {tp && (
                <Box sx={{ pl: 6, pb: 0.5 }}>
                  <TraceparentPill info={tp} />
                </Box>
              )}
            </Box>
          );
        })
      )}
    </Panel>
  );
}
