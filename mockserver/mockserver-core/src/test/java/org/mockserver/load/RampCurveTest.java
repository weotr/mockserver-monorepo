package org.mockserver.load;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Pure unit tests for {@link RampCurve#valueAt(double, double, double)} — the single source of truth
 * for ramp interpolation. No global state, so they run in the parallel Surefire phase.
 */
public class RampCurveTest {

    private static final double EPS = 1e-9;

    @Test
    public void allCurvesAreExactAtTheEndpoints() {
        for (RampCurve curve : RampCurve.values()) {
            assertThat(curve + " at p=0 returns start", curve.valueAt(10, 50, 0.0), closeTo(10.0, EPS));
            assertThat(curve + " at p=1 returns end", curve.valueAt(10, 50, 1.0), closeTo(50.0, EPS));
        }
    }

    @Test
    public void linearIsConstantSlope() {
        assertThat(RampCurve.LINEAR.valueAt(0, 100, 0.25), closeTo(25.0, EPS));
        assertThat(RampCurve.LINEAR.valueAt(0, 100, 0.5), closeTo(50.0, EPS));
        assertThat(RampCurve.LINEAR.valueAt(0, 100, 0.75), closeTo(75.0, EPS));
        // descending ramp
        assertThat(RampCurve.LINEAR.valueAt(100, 0, 0.5), closeTo(50.0, EPS));
    }

    @Test
    public void quadraticEasesIn() {
        // value(p) = start + (end-start)*p*p
        assertThat(RampCurve.QUADRATIC.valueAt(0, 100, 0.5), closeTo(25.0, EPS));
        assertThat(RampCurve.QUADRATIC.valueAt(0, 100, 0.1), closeTo(1.0, EPS));
        // at the same mid-point the quadratic is below the linear (ease-in)
        assertThat(RampCurve.QUADRATIC.valueAt(0, 100, 0.5),
            lessThan(RampCurve.LINEAR.valueAt(0, 100, 0.5)));
    }

    @Test
    public void exponentialEasesInAndHandlesZeroStart() {
        // Normalised exp form with K=4: at p=0.5 the value is below the linear midpoint (ease-in),
        // and crucially start=0 yields a finite, correct value (no NaN/Inf).
        double mid = RampCurve.EXPONENTIAL.valueAt(0, 100, 0.5);
        assertThat(mid, greaterThan(0.0));
        assertThat(mid, lessThan(50.0));
        // exact closed form: 100 * (exp(2)-1)/(exp(4)-1)
        double expected = 100.0 * (Math.exp(2.0) - 1.0) / (Math.exp(4.0) - 1.0);
        assertThat(mid, closeTo(expected, 1e-6));
        // start=0 endpoint stays exact
        assertThat(RampCurve.EXPONENTIAL.valueAt(0, 100, 0.0), closeTo(0.0, EPS));
        assertThat(RampCurve.EXPONENTIAL.valueAt(0, 100, 1.0), closeTo(100.0, EPS));
    }

    @Test
    public void progressIsClampedOutsideUnitInterval() {
        assertThat(RampCurve.LINEAR.valueAt(10, 50, -1.0), closeTo(10.0, EPS));
        assertThat(RampCurve.LINEAR.valueAt(10, 50, 2.0), closeTo(50.0, EPS));
        assertThat(RampCurve.EXPONENTIAL.valueAt(10, 50, 5.0), closeTo(50.0, EPS));
    }
}
