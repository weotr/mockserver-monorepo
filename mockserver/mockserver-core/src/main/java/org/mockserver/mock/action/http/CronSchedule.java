package org.mockserver.mock.action.http;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;

/**
 * A minimal, self-contained evaluator for standard 5-field cron expressions,
 * used to schedule a deferred chaos-experiment start.
 *
 * <p>Fields, in order: {@code minute hour day-of-month month day-of-week}.
 * Each field supports:
 * <ul>
 *   <li>{@code *} — every value</li>
 *   <li>a single value (e.g. {@code 30})</li>
 *   <li>a range (e.g. {@code 9-17})</li>
 *   <li>a step over a wildcard or range (e.g. {@code 0-59/5}, {@code 0-30/10})</li>
 *   <li>a comma list of any of the above (e.g. {@code 0,15,30,45})</li>
 * </ul>
 *
 * <p>Day-of-week is {@code 0-6} with {@code 0} = Sunday ({@code 7} is also
 * accepted as Sunday). Day-of-month and day-of-week follow the conventional cron
 * rule: when <em>both</em> are restricted (neither is {@code *}) a timestamp
 * matches if <em>either</em> matches; otherwise both must match.
 *
 * <p>Evaluation uses the JVM default time zone (matching the orchestrator's
 * wall-clock scheduler) and minute granularity. {@link #millisUntilNext(long)}
 * returns the delay from a given epoch-millis instant to the next matching
 * minute boundary, searching up to one year ahead.
 *
 * <p>This deliberately avoids a third-party cron dependency to keep the change
 * self-contained and within the project's dependency ceilings.
 */
final class CronSchedule {

    private static final int SEARCH_LIMIT_MINUTES = 366 * 24 * 60;

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months,
                         BitSet daysOfWeek, boolean domRestricted, boolean dowRestricted) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /**
     * Parses a 5-field cron expression. Throws {@link IllegalArgumentException}
     * with a human-readable message if the expression is malformed.
     */
    static CronSchedule parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron expression is blank");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                "expected 5 fields (minute hour day-of-month month day-of-week) but got " + fields.length);
        }
        BitSet minutes = parseField(fields[0], 0, 59, "minute");
        BitSet hours = parseField(fields[1], 0, 23, "hour");
        BitSet daysOfMonth = parseField(fields[2], 1, 31, "day-of-month");
        BitSet months = parseField(fields[3], 1, 12, "month");
        BitSet daysOfWeek = parseDayOfWeek(fields[4]);
        return new CronSchedule(minutes, hours, daysOfMonth, months, daysOfWeek,
            !isWildcard(fields[2]), !isWildcard(fields[4]));
    }

    /**
     * Returns the number of milliseconds from {@code fromMillis} to the next
     * matching minute boundary (strictly after the current minute). Returns
     * {@code 0} if no match is found within the search horizon (one year) — i.e.
     * the expression can never fire (e.g. an impossible date). A satisfiable cron
     * always returns a value {@code >= 60000}, so the orchestrator treats a
     * {@code 0} result as a never-matching expression and rejects it at validation
     * time.
     */
    long millisUntilNext(long fromMillis) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime from = Instant.ofEpochMilli(fromMillis).atZone(zone);
        // Advance to the start of the next minute so we never "match" the current instant.
        ZonedDateTime candidate = from.withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < SEARCH_LIMIT_MINUTES; i++, candidate = candidate.plusMinutes(1)) {
            if (matches(candidate)) {
                long delay = candidate.toInstant().toEpochMilli() - fromMillis;
                return Math.max(0L, delay);
            }
        }
        return 0L;
    }

    private boolean matches(ZonedDateTime time) {
        if (!minutes.get(time.getMinute())) {
            return false;
        }
        if (!hours.get(time.getHour())) {
            return false;
        }
        if (!months.get(time.getMonthValue())) {
            return false;
        }
        boolean domMatch = daysOfMonth.get(time.getDayOfMonth());
        // java.time DayOfWeek: MONDAY=1..SUNDAY=7; cron uses SUNDAY=0. Map SUNDAY->0.
        int dow = time.getDayOfWeek().getValue() % 7;
        boolean dowMatch = daysOfWeek.get(dow);
        if (domRestricted && dowRestricted) {
            return domMatch || dowMatch;
        }
        return domMatch && dowMatch;
    }

    private static boolean isWildcard(String field) {
        return "*".equals(field.trim());
    }

    private static BitSet parseDayOfWeek(String field) {
        BitSet set = parseField(field, 0, 7, "day-of-week");
        // Normalise 7 (Sunday) to 0 so matching only needs to check 0-6.
        if (set.get(7)) {
            set.set(0);
            set.clear(7);
        }
        return set;
    }

    private static BitSet parseField(String field, int min, int max, String name) {
        BitSet set = new BitSet(max + 1);
        for (String part : field.trim().split(",")) {
            parsePart(part.trim(), min, max, name, set);
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException(name + " field '" + field + "' matches no values");
        }
        return set;
    }

    private static void parsePart(String part, int min, int max, String name, BitSet set) {
        if (part.isEmpty()) {
            throw new IllegalArgumentException(name + " field has an empty element");
        }
        int step = 1;
        String rangePart = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            rangePart = part.substring(0, slash);
            String stepStr = part.substring(slash + 1);
            step = parseInt(stepStr, name + " step");
            if (step <= 0) {
                throw new IllegalArgumentException(name + " step must be > 0 in '" + part + "'");
            }
        }

        int rangeStart;
        int rangeEnd;
        if (rangePart.equals("*")) {
            rangeStart = min;
            rangeEnd = max;
        } else if (rangePart.contains("-")) {
            String[] bounds = rangePart.split("-", 2);
            rangeStart = parseInt(bounds[0], name);
            rangeEnd = parseInt(bounds[1], name);
        } else {
            rangeStart = parseInt(rangePart, name);
            rangeEnd = (slash >= 0) ? max : rangeStart;
        }

        if (rangeStart < min || rangeEnd > max || rangeStart > rangeEnd) {
            throw new IllegalArgumentException(
                name + " value out of range in '" + part + "' (allowed " + min + "-" + max + ")");
        }
        for (int v = rangeStart; v <= rangeEnd; v += step) {
            set.set(v);
        }
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " field has non-numeric value '" + value + "'");
        }
    }
}
