package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.Times;
import org.mockserver.test.TestLoggerExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the default behaviour is unchanged: with {@code resetBeforeEach}
 * absent (defaulting to {@code false}) an expectation set in one test DOES
 * remain visible in the next test — the shared server is NOT reset between
 * tests. This documents/guards the historic, non-isolating behaviour.
 */
@ExtendWith({
    MockServerExtension.class,
    TestLoggerExtension.class,
})
@MockServerSettings(ports = 8792)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MockServerExtensionResetBeforeEachOffTest {

    private final MockServerClient client;

    MockServerExtensionResetBeforeEachOffTest(MockServerClient client) {
        this.client = client;
    }

    @Test
    @Order(1)
    void firstTestSetsAnExpectation() {
        client.when(request().withPath("/bleed"), Times.unlimited()).respond(response().withBody("a"));
        assertThat(client.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    @Order(2)
    void secondTestStillSeesExpectationFromFirstTest() {
        // default behaviour: no per-test reset, so the expectation bleeds through
        assertThat(client.retrieveActiveExpectations(null).length, is(1));
    }
}
