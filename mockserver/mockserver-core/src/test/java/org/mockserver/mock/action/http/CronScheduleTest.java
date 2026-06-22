package org.mockserver.mock.action.http;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;

/**
 * Tests for the minimal 5-field cron evaluator used to schedule a deferred
 * chaos-experiment start. All assertions use the JVM default time zone, matching
 * the orchestrator's wall-clock scheduler.
 */
public class CronScheduleTest {

    private long epochMillis(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    public void shouldComputeDelayToNextDailyBoundary() {
        // 10:17:00 -> next 11:00 is 43 minutes away
        long now = epochMillis(2026, 6, 20, 10, 17);
        long delay = CronSchedule.parse("0 11 * * *").millisUntilNext(now);
        assertThat(delay, is(43L * 60 * 1000));
    }

    @Test
    public void shouldRollOverToNextDayWhenTimeAlreadyPassed() {
        // 12:00 with "0 11 * * *" -> next 11:00 is tomorrow, 23 hours away
        long now = epochMillis(2026, 6, 20, 12, 0);
        long delay = CronSchedule.parse("0 11 * * *").millisUntilNext(now);
        assertThat(delay, is(23L * 60 * 60 * 1000));
    }

    @Test
    public void shouldHandleStepEveryFiveMinutes() {
        // 10:02 with "*/5 * * * *" -> next matching minute is 10:05, 3 minutes away
        long now = epochMillis(2026, 6, 20, 10, 2);
        long delay = CronSchedule.parse("*/5 * * * *").millisUntilNext(now);
        assertThat(delay, is(3L * 60 * 1000));
    }

    @Test
    public void shouldNeverMatchTheCurrentMinute() {
        // exactly on a matching boundary -> next match is the following period (5 min later)
        long now = epochMillis(2026, 6, 20, 10, 5);
        long delay = CronSchedule.parse("*/5 * * * *").millisUntilNext(now);
        assertThat(delay, is(5L * 60 * 1000));
    }

    @Test
    public void shouldHandleCommaListAndRange() {
        // 10:10 with "0,30 9-17 * * *" -> next is 10:30, 20 minutes away
        long now = epochMillis(2026, 6, 20, 10, 10);
        long delay = CronSchedule.parse("0,30 9-17 * * *").millisUntilNext(now);
        assertThat(delay, is(20L * 60 * 1000));
    }

    @Test
    public void shouldMatchDayOfWeek() {
        // 2026-06-20 is a Saturday. Cron "0 0 * * 1" (Monday) -> next Monday 00:00.
        // From Sat 12:00, Monday 00:00 is 1 day + 12 hours = 36 hours away.
        long now = epochMillis(2026, 6, 20, 12, 0);
        long delay = CronSchedule.parse("0 0 * * 1").millisUntilNext(now);
        assertThat(delay, is(36L * 60 * 60 * 1000));
    }

    @Test
    public void shouldTreatSeven_AsSunday() {
        // 2026-06-20 Saturday 12:00; "0 0 * * 7" (Sunday via 7) -> next Sunday 00:00 = 12h away
        long now = epochMillis(2026, 6, 20, 12, 0);
        long delay = CronSchedule.parse("0 0 * * 7").millisUntilNext(now);
        assertThat(delay, is(12L * 60 * 60 * 1000));
    }

    @Test
    public void shouldRejectWrongFieldCount() {
        assertThrows("expected 5 fields", () -> CronSchedule.parse("0 11 * *"));
    }

    @Test
    public void shouldRejectOutOfRangeValue() {
        assertThrows("out of range", () -> CronSchedule.parse("99 11 * * *"));
    }

    @Test
    public void shouldRejectNonNumeric() {
        assertThrows("non-numeric", () -> CronSchedule.parse("abc 11 * * *"));
    }

    @Test
    public void shouldRejectZeroStep() {
        assertThrows("step must be > 0", () -> CronSchedule.parse("*/0 * * * *"));
    }

    @Test
    public void shouldRejectBlank() {
        assertThrows("blank", () -> CronSchedule.parse("   "));
    }

    private void assertThrows(String messageFragment, Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException containing '" + messageFragment + "'");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(messageFragment));
        }
    }
}
