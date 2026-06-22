package org.mockserver.verify;

/**
 * The disposition (handling outcome) of a recorded request, used to narrow a
 * {@link Verification} to only those requests that were handled in a particular way.
 *
 * <ul>
 *     <li>{@link #MOCKED} — requests that matched an expectation and received a mocked
 *     response (logged as {@code EXPECTATION_RESPONSE}).</li>
 *     <li>{@link #FORWARDED} — requests that were forwarded/proxied to an upstream server
 *     (logged as {@code FORWARDED_REQUEST}).</li>
 * </ul>
 *
 * When no disposition is set on a {@link Verification} the original behaviour is used:
 * verification counts all received requests regardless of how they were handled.
 *
 * @author jamesdbloom
 */
public enum Disposition {
    MOCKED,
    FORWARDED
}
