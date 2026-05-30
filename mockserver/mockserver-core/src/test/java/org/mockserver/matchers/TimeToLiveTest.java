package org.mockserver.matchers;

import org.junit.After;
import org.junit.Test;
import org.mockserver.time.TimeService;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class TimeToLiveTest {

    @After
    public void tearDown() {
        TimeService.reset();
        TimeService.fixedTime = false;
    }

    @Test
    public void shouldCreateCorrectObjects() {
        // when
        assertThat(TimeToLive.unlimited().isUnlimited(), is(true));
        assertThat(TimeToLive.exactly(TimeUnit.MINUTES, 5L).isUnlimited(), is(false));
        assertThat(TimeToLive.exactly(TimeUnit.MINUTES, 5L).getTimeUnit(), is(TimeUnit.MINUTES));
        assertThat(TimeToLive.exactly(TimeUnit.MINUTES, 5L).getTimeToLive(), is(5L));
    }

    @Test
    public void shouldCalculateStillLive() throws InterruptedException {
        // when
        TimeToLive timeToLive = TimeToLive.exactly(TimeUnit.MILLISECONDS, 0L);

        TimeUnit.MILLISECONDS.sleep(5);

        // then
        assertThat(timeToLive.stillAlive(), is(false));
        assertThat(TimeToLive.exactly(TimeUnit.MINUTES, 10L).stillAlive(), is(true));
    }

    @Test
    public void shouldExpireWhenClockAdvancedPastTTL() {
        // given - freeze the clock
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        TimeService.freeze(base);

        // when - create a TTL of 10 seconds
        TimeToLive timeToLive = TimeToLive.exactly(TimeUnit.SECONDS, 10L);

        // then - still alive at creation time
        assertThat(timeToLive.stillAlive(), is(true));

        // when - advance clock by 11 seconds (past TTL)
        TimeService.advance(Duration.ofSeconds(11));

        // then - expired
        assertThat(timeToLive.stillAlive(), is(false));
    }

    @Test
    public void shouldRemainAliveWhenClockAdvancedWithinTTL() {
        // given - freeze the clock
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        TimeService.freeze(base);

        // when - create a TTL of 60 seconds
        TimeToLive timeToLive = TimeToLive.exactly(TimeUnit.SECONDS, 60L);

        // then - still alive at creation time
        assertThat(timeToLive.stillAlive(), is(true));

        // when - advance clock by 30 seconds (within TTL)
        TimeService.advance(Duration.ofSeconds(30));

        // then - still alive
        assertThat(timeToLive.stillAlive(), is(true));
    }

    @Test
    public void shouldBehaveNormallyWhenUnfrozen() {
        // when - clock is not frozen, create TTL with long duration
        TimeToLive longLived = TimeToLive.exactly(TimeUnit.HOURS, 1L);

        // then
        assertThat(longLived.stillAlive(), is(true));
    }

    @Test
    public void shouldUnlimitedAlwaysBeAliveRegardlessOfClock() {
        // given - freeze and advance far into future
        TimeService.freeze(Instant.parse("2024-01-01T00:00:00Z"));
        TimeService.advance(Duration.ofDays(365 * 100));

        // then
        assertThat(TimeToLive.unlimited().stillAlive(), is(true));
    }
}
