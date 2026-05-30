package org.mockserver.time;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

public class TimeServiceTest {

    @After
    public void tearDown() {
        TimeService.reset();
        TimeService.fixedTime = false;
    }

    @Test
    public void shouldReturnRealTimeByDefault() {
        // given
        Instant before = Instant.now();

        // when
        Instant result = TimeService.now();

        // then
        Instant after = Instant.now();
        assertThat(result, greaterThanOrEqualTo(before));
        assertThat(result, lessThanOrEqualTo(after));
    }

    @Test
    public void shouldReturnCurrentTimeMillisConsistentWithNow() {
        // given
        TimeService.freeze(Instant.parse("2024-06-15T10:30:00Z"));

        // when
        long millis = TimeService.currentTimeMillis();

        // then
        assertThat(millis, is(TimeService.now().toEpochMilli()));
    }

    @Test
    public void shouldFreezeAtSpecificInstant() {
        // given
        Instant target = Instant.parse("2024-01-01T00:00:00Z");

        // when
        TimeService.freeze(target);

        // then
        assertThat(TimeService.now(), is(target));
        assertThat(TimeService.now(), is(target)); // stays frozen
        assertThat(TimeService.isFrozen(), is(true));
    }

    @Test
    public void shouldFreezeAtCurrentTimeWhenNullPassed() {
        // given
        Instant before = Instant.now();

        // when
        TimeService.freeze(null);

        // then
        Instant frozen = TimeService.now();
        Instant after = Instant.now();
        assertThat(frozen, greaterThanOrEqualTo(before));
        assertThat(frozen, lessThanOrEqualTo(after));
        // repeated calls return same value
        assertThat(TimeService.now(), is(frozen));
    }

    @Test
    public void shouldAdvanceFromFrozenTime() {
        // given
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        TimeService.freeze(base);

        // when
        TimeService.advance(Duration.ofSeconds(30));

        // then
        assertThat(TimeService.now(), is(base.plusSeconds(30)));
    }

    @Test
    public void shouldAdvanceFromRealTimeWhenNotFrozen() {
        // given - not frozen
        Instant before = Instant.now();

        // when
        TimeService.advance(Duration.ofHours(1));

        // then - should be approximately 1 hour ahead
        Instant result = TimeService.now();
        assertThat(result, greaterThan(before.plus(Duration.ofMinutes(59))));
        assertThat(TimeService.isFrozen(), is(true));
    }

    @Test
    public void shouldResetToRealTime() {
        // given
        TimeService.freeze(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(TimeService.isFrozen(), is(true));

        // when
        TimeService.reset();

        // then
        assertThat(TimeService.isFrozen(), is(false));
        Instant before = Instant.now();
        Instant result = TimeService.now();
        Instant after = Instant.now();
        assertThat(result, greaterThanOrEqualTo(before));
        assertThat(result, lessThanOrEqualTo(after));
    }

    @Test
    public void shouldSupportLegacyFixedTimeFlag() {
        // when
        TimeService.fixedTime = true;

        // then
        assertThat(TimeService.now(), is(TimeService.FIXED_INSTANT_FOR_TESTS));
        assertThat(TimeService.isFrozen(), is(true));
    }

    @Test
    public void shouldPreferFrozenInstantOverFixedTimeFlag() {
        // given
        Instant frozen = Instant.parse("2024-06-01T12:00:00Z");
        TimeService.fixedTime = true;
        TimeService.freeze(frozen);

        // when/then - frozen instant takes precedence
        assertThat(TimeService.now(), is(frozen));
    }

    @Test
    public void shouldAdvanceFromFixedInstantForTestsWhenFixedTimeFlagSet() {
        // given - fixedTime=true but frozenInstant is null
        TimeService.fixedTime = true;
        assertThat(TimeService.now(), is(TimeService.FIXED_INSTANT_FOR_TESTS));

        // when
        TimeService.advance(Duration.ofSeconds(45));

        // then - should base from FIXED_INSTANT_FOR_TESTS, not Instant.now()
        Instant expected = TimeService.FIXED_INSTANT_FOR_TESTS.plusSeconds(45);
        assertThat(TimeService.now(), is(expected));
    }

    @Test
    public void shouldBeThreadSafe() throws InterruptedException {
        // given
        Instant target = Instant.parse("2024-01-01T00:00:00Z");
        TimeService.freeze(target);
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Instant> mismatch = new AtomicReference<>(null);

        // when
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    Instant result = TimeService.now();
                    if (!target.equals(result)) {
                        mismatch.set(result);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();

        // then
        assertThat("Thread saw wrong instant: " + mismatch.get(), mismatch.get(), is(nullValue()));
    }
}
