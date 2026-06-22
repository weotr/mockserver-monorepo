package org.mockserver.metrics;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Unit tests for {@link MetricLabels#routeOf(String)} — the low-cardinality route templatizer.
 * No global state, so this runs in the parallel test phase.
 */
public class MetricLabelsTest {

    @Test
    public void collapsesNumericIdSegment() {
        assertThat(MetricLabels.routeOf("/api/orders/12345"), is("/api/orders/{id}"));
    }

    @Test
    public void collapsesUuidSegment() {
        assertThat(MetricLabels.routeOf("/users/9f1c8e0a-1b2c-4d3e-8f90-abcdef012345"), is("/users/{id}"));
    }

    @Test
    public void collapsesLongHexSegment() {
        assertThat(MetricLabels.routeOf("/v2/items/deadbeefcafebabe"), is("/v2/items/{id}"));
    }

    @Test
    public void collapsesMultipleIdSegments() {
        assertThat(MetricLabels.routeOf("/api/orders/12345/items/67890"), is("/api/orders/{id}/items/{id}"));
    }

    @Test
    public void keepsNonIdSegmentsVerbatim() {
        assertThat(MetricLabels.routeOf("/api/orders"), is("/api/orders"));
        assertThat(MetricLabels.routeOf("/v2/items"), is("/v2/items"));
    }

    @Test
    public void dropsQueryString() {
        assertThat(MetricLabels.routeOf("/api/orders/12345?expand=true"), is("/api/orders/{id}"));
    }

    @Test
    public void shortHexTokenIsNotCollapsed() {
        // "abc" is hex but only 3 chars — genuine route structure, kept verbatim.
        assertThat(MetricLabels.routeOf("/api/abc"), is("/api/abc"));
    }

    @Test
    public void nullOrBlankPathBecomesRoot() {
        assertThat(MetricLabels.routeOf(null), is("/"));
        assertThat(MetricLabels.routeOf(""), is("/"));
    }
}
