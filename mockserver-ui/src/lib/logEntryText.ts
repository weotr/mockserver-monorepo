/**
 * Pure-function helper extracted from LogEntry.tsx so the component file only
 * exports React components (satisfies react-refresh/only-export-components).
 */
import type { LogEntryValue } from '../types';

/** Flatten a log entry's description + message parts into a single plain-text string. */
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
