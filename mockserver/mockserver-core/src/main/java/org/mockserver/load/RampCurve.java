package org.mockserver.load;

/**
 * The interpolation curve used to ramp a value (virtual users or arrival rate) from a
 * {@code start} setpoint to an {@code end} setpoint across a {@link LoadStage} as its
 * progress {@code p} advances over {@code [0, 1]}.
 *
 * <p>{@link #valueAt(double, double, double)} is the single, pure, tested source of truth for
 * the curve math so both the closed-model VU driver and the open-model arrival-rate scheduler
 * interpolate identically. At the endpoints every curve is exact: {@code valueAt(start,end,0)==start}
 * and {@code valueAt(start,end,1)==end}.
 *
 * <ul>
 *   <li>{@link #LINEAR} — {@code start + (end-start)*p} (constant slope).</li>
 *   <li>{@link #QUADRATIC} — {@code start + (end-start)*p*p} (ease-in: slow then fast).</li>
 *   <li>{@link #EXPONENTIAL} — {@code start + (end-start) * (exp(K*p)-1)/(exp(K)-1)} with {@code K=4}
 *       (a steeper ease-in; this normalised form is exact at the endpoints and handles
 *       {@code start=0} correctly).</li>
 * </ul>
 */
public enum RampCurve {

    LINEAR,
    QUADRATIC,
    EXPONENTIAL;

    /** Fixed curvature constant for {@link #EXPONENTIAL}. */
    static final double EXPONENTIAL_K = 4.0;

    /**
     * Interpolate between {@code start} and {@code end} at progress {@code p} using this curve.
     * {@code p} is clamped to {@code [0, 1]} so an over-run stage stays pinned at {@code end}.
     */
    public double valueAt(double start, double end, double p) {
        double progress = p < 0 ? 0 : (p > 1 ? 1 : p);
        double shaped;
        switch (this) {
            case QUADRATIC:
                shaped = progress * progress;
                break;
            case EXPONENTIAL:
                shaped = (Math.exp(EXPONENTIAL_K * progress) - 1.0) / (Math.exp(EXPONENTIAL_K) - 1.0);
                break;
            case LINEAR:
            default:
                shaped = progress;
                break;
        }
        return start + (end - start) * shaped;
    }
}
