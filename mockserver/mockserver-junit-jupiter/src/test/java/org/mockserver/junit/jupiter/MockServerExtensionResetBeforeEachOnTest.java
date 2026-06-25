package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;
import org.mockserver.test.TestLoggerExtension;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies that with {@code resetBeforeEach = true} an expectation set in one
 * test does NOT bleed into the next test — the shared server is reset between
 * tests. Methods are ordered so the "set" test runs before the "assert clean"
 * test on the same shared server instance.
 * <p>
 * The second test is non-vacuous: it first asserts (via {@link #firstTestRan})
 * that the first test really did run before it, so an empty expectation list
 * can only mean the reset cleared the first test's expectation — not that the
 * first test never ran.
 */
@ExtendWith({
    MockServerExtension.class,
    TestLoggerExtension.class,
})
@MockServerSettings(ports = 8791, resetBeforeEach = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MockServerExtensionResetBeforeEachOnTest {

    private static final AtomicBoolean firstTestRan = new AtomicBoolean(false);

    private final MockServerClient client;

    MockServerExtensionResetBeforeEachOnTest(MockServerClient client) {
        this.client = client;
    }

    @Test
    @Order(1)
    void firstTestSetsAnExpectation() {
        // beforeEach reset has already run, so we start clean
        assertThat(client.retrieveActiveExpectations(null).length, is(0));
        client.when(request().withPath("/bleed"), Times.unlimited()).respond(response().withBody("a"));
        assertThat(client.retrieveActiveExpectations(null).length, is(1));
        firstTestRan.set(true);
    }

    @Test
    @Order(2)
    void secondTestSeesNoBleedFromFirstTest() {
        // prove the first test actually ran before this one (defeats a vacuous pass)
        assertThat("first test must run before second", firstTestRan.get(), is(true));
        // because resetBeforeEach is on, the first test's expectation must be gone
        assertThat(client.retrieveActiveExpectations(null).length, is(0));
    }
}
