package org.mockserver.verify;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockserver.verify.VerificationTimes.*;

/**
 * @author jamesdbloom
 */
public class VerificationTimesTest {

    @Test
    public void shouldCreateCorrectObjectForAtLeast() {
        // when
        VerificationTimes times = atLeast(2);

        // then
        assertThat(times.getAtLeast(), is(2));
        assertThat(times.getAtMost(), is(-1));
    }

    @Test
    public void shouldCreateCorrectObjectForAtMost() {
        // when
        VerificationTimes times = atMost(2);

        // then
        assertThat(times.getAtLeast(), is(-1));
        assertThat(times.getAtMost(), is(2));
    }

    @Test
    public void shouldCreateCorrectObjectForNever() {
        // when
        VerificationTimes times = never();

        //then
        assertThat(times.getAtLeast(), is(0));
        assertThat(times.getAtMost(), is(0));
    }

    @Test
    public void shouldCreateCorrectObjectForOnce() {
        // when
        VerificationTimes times = once();

        // then
        assertThat(times.getAtLeast(), is(1));
        assertThat(times.getAtMost(), is(1));
    }

    @Test
    public void shouldCreateCorrectObjectForExactly() {
        // when
        VerificationTimes times = exactly(2);

        // then
        assertThat(times.getAtLeast(), is(2));
        assertThat(times.getAtMost(), is(2));
    }

    @Test
    public void shouldCreateCorrectObjectForBetween() {
        // when
        VerificationTimes times = between(1,2);

        // then
        assertThat(times.getAtLeast(), is(1));
        assertThat(times.getAtMost(), is(2));
    }

    @Test
    public void shouldMatchBetweenCorrectly() {
        // when
        VerificationTimes times = between(1,2);

        // then
        assertThat(times.matches(0), is(false));
        assertThat(times.matches(1), is(true));
        assertThat(times.matches(2), is(true));
        assertThat(times.matches(3), is(false));
    }

    @Test
    public void shouldMatchExactCorrectly() {
        // when
        VerificationTimes times = exactly(2);

        // then
        assertThat(times.matches(0), is(false));
        assertThat(times.matches(1), is(false));
        assertThat(times.matches(2), is(true));
        assertThat(times.matches(3), is(false));
    }

    @Test
    public void shouldMatchAtLeastCorrectly() {
        // when
        VerificationTimes times = atLeast(2);

        // then
        assertThat(times.matches(0), is(false));
        assertThat(times.matches(1), is(false));
        assertThat(times.matches(2), is(true));
        assertThat(times.matches(3), is(true));
    }

    @Test
    public void shouldMatchAtMostCorrectly() {
        // when
        VerificationTimes times = atMost(2);

        // then
        assertThat(times.matches(0), is(true));
        assertThat(times.matches(1), is(true));
        assertThat(times.matches(2), is(true));
        assertThat(times.matches(3), is(false));
    }

    @Test
    public void shouldMatchAtMostZeroCorrectly() {
        // when
        VerificationTimes times = atMost(0);

        // then
        assertThat(times.matches(0), is(true));
        assertThat(times.matches(1), is(false));
        assertThat(times.matches(2), is(false));
        assertThat(times.matches(3), is(false));
    }

    @Test
    public void shouldRejectNegativeExactly() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> exactly(-1));

        // then
        assertThat(exception.getMessage(), is("count must not be negative but was -1"));
    }

    @Test
    public void shouldRejectNegativeAtLeast() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> atLeast(-1));

        // then
        assertThat(exception.getMessage(), is("count must not be negative but was -1"));
    }

    @Test
    public void shouldRejectNegativeAtMost() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> atMost(-1));

        // then
        assertThat(exception.getMessage(), is("count must not be negative but was -1"));
    }

    @Test
    public void shouldRejectNegativeBetweenAtLeast() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> between(-2, 2));

        // then
        assertThat(exception.getMessage(), is("atLeast must not be negative but was -2"));
    }

    @Test
    public void shouldRejectNegativeBetweenAtMost() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> between(1, -2));

        // then
        assertThat(exception.getMessage(), is("atMost must not be negative but was -2"));
    }

    @Test
    public void shouldAllowUnboundedSentinelInBetween() {
        // the -1 unbounded sentinel must still be accepted so atLeast-only / atMost-only
        // verifications round-trip through serialisation

        // when
        VerificationTimes atLeastOnly = between(3, -1);
        VerificationTimes atMostOnly = between(-1, 2);

        // then
        assertThat(atLeastOnly.getAtLeast(), is(3));
        assertThat(atLeastOnly.getAtMost(), is(-1));
        assertThat(atMostOnly.getAtLeast(), is(-1));
        assertThat(atMostOnly.getAtMost(), is(2));
    }

    @Test
    public void shouldGenerateCorrectToString() {
        // then
        assertThat(never().toString(), is("exactly 0 times"));
        assertThat(once().toString(), is("exactly once"));
        assertThat(exactly(0).toString(), is("exactly 0 times"));
        assertThat(exactly(1).toString(), is("exactly once"));
        assertThat(exactly(2).toString(), is("exactly 2 times"));
        assertThat(atLeast(0).toString(), is("at least 0 times"));
        assertThat(atLeast(1).toString(), is("at least once"));
        assertThat(atLeast(2).toString(), is("at least 2 times"));
        assertThat(atMost(0).toString(), is("at most 0 times"));
        assertThat(atMost(1).toString(), is("at most once"));
        assertThat(atMost(2).toString(), is("at most 2 times"));
        assertThat(between(1, 2).toString(), is("between 1 and 2 times"));
        assertThat(between(1, 1).toString(), is("exactly once"));
        assertThat(between(2, 2).toString(), is("exactly 2 times"));
    }
}
