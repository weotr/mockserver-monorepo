package org.mockserver.load;

/**
 * Lifecycle state of a registered load scenario in the load-scenario registry.
 *
 * <p>A scenario is first <em>loaded</em> (registered) into the registry — it is idle and drives no
 * traffic. It is then <em>triggered</em> by name to start. If it has a positive
 * {@link LoadScenario#getStartDelayMillis() startDelayMillis} it sits in {@link #PENDING} until the
 * delay elapses, then runs; otherwise it runs immediately. When it finishes naturally it is
 * {@link #COMPLETED}; when stopped by an operator it is {@link #STOPPED}. A scenario can be
 * re-triggered from any terminal state and re-runs from the start.
 *
 * <pre>
 *   LOADED --trigger--> PENDING --delay elapsed--> RUNNING --finish--> COMPLETED
 *      ^                   |                          |
 *      |                   +--------- stop -----------+--> STOPPED
 *      +---------------------------- (re-trigger) --------------+
 * </pre>
 */
public enum LoadScenarioState {
    /** Registered into the registry, idle, driving no traffic. */
    LOADED,
    /** Triggered to start but waiting out {@link LoadScenario#getStartDelayMillis()}. */
    PENDING,
    /** Actively driving load (stage clock running). */
    RUNNING,
    /** Finished all stages (or reached {@code maxRequests}) naturally. */
    COMPLETED,
    /** Stopped by an explicit stop request before completing. */
    STOPPED
}
