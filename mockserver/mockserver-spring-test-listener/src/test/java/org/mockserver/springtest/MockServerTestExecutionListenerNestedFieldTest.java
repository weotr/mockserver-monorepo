package org.mockserver.springtest;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;

/**
 * Regression test for issue #2371: injecting the MockServerClient declared on an outer class into a
 * {@code @Nested} inner test instance must set the field on the enclosing instance, not on the inner
 * instance (which previously threw IllegalArgumentException).
 */
public class MockServerTestExecutionListenerNestedFieldTest {

    private static MockServerClient client;

    @BeforeClass
    public static void createClient() {
        // Only a non-null MockServerClient reference is needed for the identity assertions below. The
        // constructor performs no network I/O (it connects lazily on the first API call), and we never
        // call an API method or close() - so this test issues no requests and must not be closed
        // (close() would fire a real PUT /stop to localhost:1080, a cross-test side effect). The idle
        // event-loop threads are daemon threads reaped at JVM exit.
        client = new MockServerClient("localhost", 1080);
    }

    static class Outer {
        MockServerClient mockServerClient;

        class Inner {
        }
    }

    static class OuterWithoutField {
        class Inner {
        }
    }

    @Test
    public void injectsIntoOuterFieldFromNestedInstance() {
        Outer outer = new Outer();
        Outer.Inner inner = outer.new Inner();

        // must not throw IllegalArgumentException (the #2371 regression)
        MockServerTestExecutionListener.injectMockServerClient(inner, client);

        assertThat(outer.mockServerClient, is(sameInstance(client)));
    }

    @Test
    public void injectsIntoOwnFieldForNonNestedInstance() {
        Outer outer = new Outer();

        MockServerTestExecutionListener.injectMockServerClient(outer, client);

        assertThat(outer.mockServerClient, is(sameInstance(client)));
    }

    @Test
    public void doesNothingWhenNoMockServerClientField() {
        OuterWithoutField.Inner inner = new OuterWithoutField().new Inner();

        // no MockServerClient field anywhere in the hierarchy - must be a no-op, not an exception
        MockServerTestExecutionListener.injectMockServerClient(inner, client);
    }
}
