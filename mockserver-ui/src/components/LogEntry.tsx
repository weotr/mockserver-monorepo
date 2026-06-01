import { useState, useMemo, Fragment } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import HelpOutlinedIcon from '@mui/icons-material/HelpOutlined';
import AutoFixHighIcon from '@mui/icons-material/AutoFixHigh';
import type { LogEntryValue, MessagePart } from '../types';
import JsonViewer from './JsonViewer';
import BecauseSection from './BecauseSection';
import CopyButton from './CopyButton';
import { useDebugMismatchContext } from '../hooks/DebugMismatchContext';
import { useGenerateStubContext } from '../hooks/GenerateStubContext';

// ---------------------------------------------------------------------------
// W3C traceparent pill (F8)
// ---------------------------------------------------------------------------

interface TraceparentInfo {
  raw: string;
  version: string;
  traceId: string;
  parentId: string;
  flags: string;
}

const TRACEPARENT_REGEX = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i;

function parseTraceparent(value: string): TraceparentInfo | null {
  const match = TRACEPARENT_REGEX.exec(value.trim());
  if (!match) return null;
  return {
    raw: value.trim(),
    version: match[1]!,
    traceId: match[2]!,
    parentId: match[3]!,
    flags: match[4]!,
  };
}

/**
 * Search for a `traceparent` header in a JSON object that looks like an HTTP
 * request or response. Headers can be in array-of-{name,values} form or an
 * object map — both are handled.
 */
function findTraceparentInObject(obj: Record<string, unknown>): TraceparentInfo | null {
  const headers = obj['headers'];
  if (!headers) return null;

  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (typeof h !== 'object' || h === null) continue;
      const entry = h as Record<string, unknown>;
      const name = entry['name'];
      if (typeof name === 'string' && name.toLowerCase() === 'traceparent') {
        const values = entry['values'];
        if (Array.isArray(values) && values.length > 0 && typeof values[0] === 'string') {
          const parsed = parseTraceparent(values[0]);
          if (parsed) return parsed;
        }
      }
    }
  } else if (typeof headers === 'object' && headers !== null) {
    const map = headers as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === 'traceparent') {
        const val = map[key];
        if (typeof val === 'string') {
          const parsed = parseTraceparent(val);
          if (parsed) return parsed;
        }
        if (Array.isArray(val) && val.length > 0 && typeof val[0] === 'string') {
          const parsed = parseTraceparent(val[0]);
          if (parsed) return parsed;
        }
      }
    }
  }

  return null;
}

function extractTraceparent(entry: LogEntryValue): TraceparentInfo | null {
  if (!entry.messageParts) return null;
  for (const part of entry.messageParts) {
    if (part.json && typeof part.value === 'object' && part.value !== null && !Array.isArray(part.value)) {
      const obj = part.value as Record<string, unknown>;
      // Check the object itself (may be an httpRequest / httpResponse)
      const found = findTraceparentInObject(obj);
      if (found) return found;
      // Check nested httpRequest / httpResponse
      for (const nested of ['httpRequest', 'httpResponse']) {
        const inner = obj[nested];
        if (inner && typeof inner === 'object' && !Array.isArray(inner)) {
          const innerFound = findTraceparentInObject(inner as Record<string, unknown>);
          if (innerFound) return innerFound;
        }
      }
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
          ml: 0.5,
          '& .MuiChip-label': { px: 0.5 },
        }}
      />
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------

interface LogEntryProps {
  entry: LogEntryValue;
  indent?: boolean;
  divider?: boolean;
  collapsible?: boolean;
}

function addLinks(value: string) {
  const urlMatch = value.match(/(https?:\/\/[^\s]*)/);
  if (urlMatch) {
    const matchedUrl = urlMatch[0]!;
    const idx = value.indexOf(matchedUrl);
    return (
      <span>
        {value.substring(0, idx)}
        <a
          href={matchedUrl}
          target="_blank"
          rel="noopener noreferrer"
          style={{ textDecoration: 'underline', color: 'rgb(95, 113, 245)' }}
        >
          {matchedUrl}
        </a>
        {value.substring(idx + matchedUrl.length)}
      </span>
    );
  }
  return value;
}

function renderMessagePart(part: MessagePart) {
  if (part.value === undefined || part.value === null) return null;

  if (!part.argument) {
    return (
      <Box key={part.key} component="span" sx={{ fontFamily: 'monospace' }}>
        {addLinks(String(part.value))}
      </Box>
    );
  }

  if (part.because && Array.isArray(part.value)) {
    return <BecauseSection key={part.key} reasons={part.value as string[]} />;
  }

  if (part.multiline && Array.isArray(part.value)) {
    return <BecauseSection key={part.key} reasons={part.value as string[]} />;
  }

  if (part.json) {
    if (typeof part.value === 'object' && part.value !== null) {
      return (
        // Trailing non-breaking space separates the expandable JSON block from
        // the following text (e.g. "} matched expectation:") which would
        // otherwise butt directly against the closing brace.
        <Fragment key={part.key}>
          <Box sx={{ display: 'inline-block', pl: 0.5 }}>
            <JsonViewer
              data={part.value as Record<string, unknown>}
              collapsed={0}
              enableClipboard={true}
            />
          </Box>
          {'\u00A0'}
        </Fragment>
      );
    }
    return (
      <Box key={part.key} component="span" sx={{ fontFamily: 'monospace', pl: 0.5 }}>
        {String(part.value)}
      </Box>
    );
  }

  return (
    <Box
      key={part.key}
      component="span"
      sx={{ fontFamily: 'monospace', pl: 0.5, letterSpacing: '0.08em', whiteSpace: 'pre' }}
    >
      {addLinks(String(part.value))}
    </Box>
  );
}

export function entryToText(entry: LogEntryValue): string {
  const parts: string[] = [];
  if (entry.description) {
    if (typeof entry.description === 'string') {
      parts.push(entry.description);
    } else if (entry.description.json === false) {
      parts.push(`${entry.description.first} ${entry.description.second}`);
    } else {
      parts.push(entry.description.first);
    }
  }
  if (entry.messageParts) {
    for (const p of entry.messageParts) {
      if (typeof p.value === 'string') parts.push(p.value);
      else if (Array.isArray(p.value)) parts.push(p.value.join('\n'));
      else if (typeof p.value === 'object') parts.push(JSON.stringify(p.value, null, 2));
      else parts.push(String(p.value));
    }
  }
  return parts.join(' ').trim();
}

function getSummary(entry: LogEntryValue): string {
  if (!entry.messageParts || entry.messageParts.length === 0) return '';
  const firstTextPart = entry.messageParts.find(
    (p) => typeof p.value === 'string' && p.value.trim().length > 0,
  );
  if (!firstTextPart) return '';
  const text = String(firstTextPart.value).trim().split('\n')[0]!;
  return text.length > 80 ? text.substring(0, 80) + '…' : text;
}

function descriptionText(entry: LogEntryValue): string {
  if (!entry.description) return '';
  if (typeof entry.description === 'string') return entry.description;
  if (entry.description.json === false) {
    return `${entry.description.first} ${entry.description.second}`;
  }
  return entry.description.first;
}

function isNotMatchedEntry(entry: LogEntryValue): boolean {
  const desc = descriptionText(entry);
  return desc.includes('EXPECTATION_NOT_MATCHED');
}

function extractRequestFromEntry(entry: LogEntryValue): Record<string, unknown> | null {
  if (!entry.messageParts) return null;
  const jsonParts = entry.messageParts.filter(
    (p) => p.json && p.argument && typeof p.value === 'object' && p.value !== null,
  );
  if (jsonParts.length >= 2) {
    return jsonParts[1]!.value as Record<string, unknown>;
  }
  if (jsonParts.length === 1) {
    return jsonParts[0]!.value as Record<string, unknown>;
  }
  return null;
}

export default function LogEntry({ entry, indent = false, divider = false, collapsible = false }: LogEntryProps) {
  const style = entry.style ?? {};
  const hasBody = entry.messageParts && entry.messageParts.length > 0;
  const canCollapse = collapsible && hasBody;
  const [expanded, setExpanded] = useState(false);
  const debugMismatch = useDebugMismatchContext();
  const generateStub = useGenerateStubContext();
  const isUnmatched = isNotMatchedEntry(entry);
  const showWhyButton = isUnmatched && debugMismatch !== null;
  const showGenerateStubButton = isUnmatched && generateStub !== null;
  const traceparent = useMemo(() => extractTraceparent(entry), [entry]);

  return (
    <Box
      sx={{
        pl: indent ? 4 : 0.5,
        pr: 0.5,
        py: 0.5,
        fontSize: indent ? '0.8em' : '0.85em',
        whiteSpace: style['style.whiteSpace'] || style['whiteSpace'] || 'nowrap',
        overflow: 'auto',
        color: style.color ?? 'inherit',
        position: 'relative',
        '&:hover .copy-btn': { opacity: 1 },
        ...(divider && {
          borderBottom: 1,
          borderColor: 'divider',
          '&:last-child': { borderBottom: 0 },
        }),
      }}
    >
      {canCollapse ? (
        <>
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
              sx={{ whiteSpace: 'pre', fontFamily: 'monospace' }}
            >
              {descriptionText(entry) || 'SYSTEM_MESSAGE'}
            </Box>
            {traceparent && <TraceparentPill info={traceparent} />}
            {showWhyButton && (
              <Tooltip title="Analyze why this request didn't match">
                <IconButton
                  size="small"
                  sx={{ p: 0, ml: 0.5, '& .MuiSvgIcon-root': { fontSize: '0.9rem' } }}
                  onClick={(e) => {
                    e.stopPropagation();
                    const request = extractRequestFromEntry(entry);
                    if (request && debugMismatch) {
                      void debugMismatch(request);
                    }
                  }}
                >
                  <HelpOutlinedIcon sx={{ color: 'warning.main' }} />
                </IconButton>
              </Tooltip>
            )}
            {showGenerateStubButton && (
              <Tooltip title="Generate an expectation for this unmatched request">
                <IconButton
                  size="small"
                  sx={{ p: 0, ml: 0.5, '& .MuiSvgIcon-root': { fontSize: '0.9rem' } }}
                  onClick={(e) => {
                    e.stopPropagation();
                    const request = extractRequestFromEntry(entry);
                    if (request && generateStub) {
                      void generateStub(request);
                    }
                  }}
                >
                  <AutoFixHighIcon sx={{ color: 'info.main' }} />
                </IconButton>
              </Tooltip>
            )}
            {!expanded && (
              <Box
                component="span"
                sx={{ fontFamily: 'monospace', color: 'text.secondary', ml: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}
              >
                {getSummary(entry)}
              </Box>
            )}
          </Box>
          {expanded && (
            <Box sx={{ pl: 2.5, pt: 0.5 }}>
              {entry.messageParts?.map(renderMessagePart)}
            </Box>
          )}
        </>
      ) : (
        <>
          {entry.description && (
            <Box
              sx={{
                whiteSpace: 'pre',
                fontFamily: 'monospace',
                display: 'flex',
                alignItems: 'center',
              }}
            >
              {descriptionText(entry)}
              {traceparent && <TraceparentPill info={traceparent} />}
            </Box>
          )}
          {entry.messageParts?.map(renderMessagePart)}
        </>
      )}
      <Box className="copy-btn" sx={{ position: 'absolute', top: 2, right: 2, opacity: 0 }}>
        <CopyButton text={entryToText(entry)} />
      </Box>
    </Box>
  );
}
