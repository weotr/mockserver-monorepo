package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ExpectationId;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Guard test for the candidate index generation contract: every control-plane operation
 * that structurally changes {@code httpRequestMatchers} (or a matcher's method/path) MUST
 * bump the modification counter, because the {@link CandidateIndex} rebuilds lazily only
 * when that counter advances. A future mutation site that forgets to bump it would leave a
 * stale index serving wrong/old expectations above the threshold — this test fails the
 * build instead.
 *
 * <p>Mutates no global/system state, so it is safe in the parallel surefire phase.
 */
public class RequestMatchersCandidateIndexGenerationTest {

    private RequestMatchers newMatchers() {
        return new RequestMatchers(
            configuration(), new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    private static Expectation expectation(String path, String body) {
        return new Expectation(request().withMethod("GET").withPath(path)).thenRespond(response().withBody(body));
    }

    @Test
    public void addBumpsGeneration() {
        RequestMatchers matchers = newMatchers();
        long before = matchers.matchersModificationCountForTesting();
        matchers.add(expectation("/a", "a"), API);
        assertThat(matchers.matchersModificationCountForTesting(), greaterThan(before));
    }

    @Test
    public void updateInPlaceBumpsGeneration() {
        RequestMatchers matchers = newMatchers();
        Expectation original = expectation("/a", "v1");
        matchers.add(original, API);
        long before = matchers.matchersModificationCountForTesting();
        // re-add same id with a different path -> update-in-place that changes the bucket
        Expectation updated = expectation("/b", "v2").withId(original.getId());
        matchers.add(updated, API);
        assertThat(matchers.matchersModificationCountForTesting(), greaterThan(before));
    }

    @Test
    public void batchUpdateBumpsGeneration() {
        RequestMatchers matchers = newMatchers();
        long before = matchers.matchersModificationCountForTesting();
        matchers.update(new Expectation[]{expectation("/a", "a"), expectation("/b", "b")}, API);
        assertThat(matchers.matchersModificationCountForTesting(), greaterThan(before));
    }

    @Test
    public void clearByIdBumpsGeneration() {
        RequestMatchers matchers = newMatchers();
        Expectation e = expectation("/a", "a");
        matchers.add(e, API);
        long before = matchers.matchersModificationCountForTesting();
        matchers.clear(ExpectationId.expectationId(e.getId()), "corr");
        assertThat(matchers.matchersModificationCountForTesting(), greaterThan(before));
    }

    @Test
    public void clearByRequestBumpsGeneration() {
        RequestMatchers matchers = newMatchers();
        matchers.add(expectation("/a", "a"), API);
        long before = matchers.matchersModificationCountForTesting();
        matchers.clear(request().withMethod("GET").withPath("/a"));
        assertThat(matchers.matchersModificationCountForTesting(), greaterThan(before));
    }

    @Test
    public void resetBumpsGeneration() {
        RequestMatchers matchers = newMatchers();
        matchers.add(expectation("/a", "a"), API);
        long before = matchers.matchersModificationCountForTesting();
        matchers.reset();
        assertThat(matchers.matchersModificationCountForTesting(), greaterThan(before));
    }
}
