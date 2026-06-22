package org.mockserver.load;

/**
 * The kind of a {@link LoadStage} in a {@link LoadProfile}.
 *
 * <ul>
 *   <li>{@link #VU} — a closed-model stage: hold or ramp the number of concurrent virtual users.
 *       Each VU loops the scenario steps back-to-back; throughput is whatever the target can sustain.</li>
 *   <li>{@link #RATE} — an open-model stage: hold or ramp a target <em>arrival rate</em> in iterations
 *       per second. The orchestrator starts new iterations to match the integral of the rate over time,
 *       auto-scaling a VU pool to run them, independent of how fast the target responds.</li>
 *   <li>{@link #PAUSE} — drive no load for the duration; VUs drain and the next stage starts cold.</li>
 * </ul>
 */
public enum LoadStageType {
    VU,
    RATE,
    PAUSE
}
