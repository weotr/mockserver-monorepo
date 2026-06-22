import { describe, it, expect } from 'vitest';
import {
  parseLogTimestamp,
  formatCompactTime,
  formatAbsoluteTime,
} from '../lib/logEntryTime';

describe('parseLogTimestamp', () => {
  it('parses the MockServer "yyyy-MM-dd HH:mm:ss.SSS" format', () => {
    const parsed = parseLogTimestamp('2025-05-05 10:57:18.895');
    expect(parsed.raw).toBe('2025-05-05 10:57:18.895');
    expect(parsed.date).not.toBeNull();
    const d = parsed.date!;
    // Interpreted as local time (matches how the server formats it).
    expect(d.getFullYear()).toBe(2025);
    expect(d.getMonth()).toBe(4); // May (0-indexed)
    expect(d.getDate()).toBe(5);
    expect(d.getHours()).toBe(10);
    expect(d.getMinutes()).toBe(57);
    expect(d.getSeconds()).toBe(18);
    expect(d.getMilliseconds()).toBe(895);
  });

  it('parses a value without milliseconds', () => {
    const parsed = parseLogTimestamp('2025-05-05 10:57:18');
    expect(parsed.date).not.toBeNull();
    expect(parsed.date!.getMilliseconds()).toBe(0);
  });

  it('accepts the ISO "T" separator too', () => {
    const parsed = parseLogTimestamp('2025-05-05T10:57:18.895');
    expect(parsed.date).not.toBeNull();
    expect(parsed.date!.getHours()).toBe(10);
  });

  it('trims surrounding whitespace', () => {
    const parsed = parseLogTimestamp('  2025-05-05 10:57:18.895  ');
    expect(parsed.raw).toBe('2025-05-05 10:57:18.895');
    expect(parsed.date).not.toBeNull();
  });

  it('returns a null date for an unparseable string but keeps the raw value', () => {
    const parsed = parseLogTimestamp('not-a-date');
    expect(parsed.raw).toBe('not-a-date');
    expect(parsed.date).toBeNull();
  });
});

describe('formatCompactTime / formatAbsoluteTime', () => {
  it('formats a parsed time and falls back to the raw string otherwise', () => {
    const good = parseLogTimestamp('2025-05-05 10:57:18.895');
    expect(formatCompactTime(good)).not.toBe('2025-05-05 10:57:18.895');
    expect(formatCompactTime(good).length).toBeGreaterThan(0);
    expect(formatAbsoluteTime(good).length).toBeGreaterThan(0);

    const bad = parseLogTimestamp('garbage');
    expect(formatCompactTime(bad)).toBe('garbage');
    expect(formatAbsoluteTime(bad)).toBe('garbage');
  });
});
