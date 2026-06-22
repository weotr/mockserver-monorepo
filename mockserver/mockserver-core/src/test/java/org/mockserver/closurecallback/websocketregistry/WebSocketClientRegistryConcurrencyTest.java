package org.mockserver.closurecallback.websocketregistry;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Concurrency stress test for {@link WebSocketClientRegistry}.
 * <p>
 * The callback registries ({@code responseCallbackRegistry}, {@code forwardCallbackRegistry},
 * {@code streamFrameCallbackRegistry}) are mutated by action-handler threads while the Netty
 * I/O thread reads them, and {@code reset()} iterates {@code clientRegistry}. Before the fix
 * the callback registries were plain {@link org.mockserver.collections.CircularHashMap}
 * (a {@link java.util.LinkedHashMap} subclass) with no synchronization, so concurrent
 * put/get/remove/iterate could corrupt the map (lost callbacks, {@link java.util.ConcurrentModificationException},
 * or an infinite loop in the linked-list rehash).
 * <p>
 * This test hammers all the mutation/read paths from many threads and asserts that no
 * exception escapes and that the registry returns to a consistent (empty) final state.
 */
public class WebSocketClientRegistryConcurrencyTest {

    private static final int THREADS = 16;
    private static final int ITERATIONS = 2_000;

    private WebSocketClientRegistry newRegistry() {
        // a deliberately small bound so the CircularHashMap is actively evicting under load
        Configuration configuration = Configuration.configuration().maxWebSocketExpectations(64);
        return new WebSocketClientRegistry(configuration, new MockServerLogger());
    }

    @Test
    public void shouldSurviveConcurrentCallbackRegistrationMutationAndReads() throws Exception {
        final WebSocketClientRegistry registry = newRegistry();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch go = new CountDownLatch(1);

        Runnable mutator = () -> {
            ready.countDown();
            try {
                go.await();
                final long threadId = Thread.currentThread().getId();
                for (int i = 0; i < ITERATIONS && !stop.get(); i++) {
                    String correlationId = threadId + "-" + i;
                    registry.registerResponseCallbackHandler(correlationId, response -> { });
                    registry.registerForwardCallbackHandler(correlationId, new WebSocketRequestCallback() {
                        @Override
                        public void handle(org.mockserver.model.HttpRequest httpRequest) {
                        }

                        @Override
                        public void handleError(HttpResponse httpResponse) {
                        }
                    });
                    registry.registerStreamFrameCallbackHandler(correlationId, decision -> { });
                    // read paths (exercised by the Netty I/O thread in production)
                    registry.size();
                    // remove half the time to interleave put/remove
                    if ((i & 1) == 0) {
                        registry.unregisterResponseCallbackHandler(correlationId);
                        registry.unregisterForwardCallbackHandler(correlationId);
                        registry.unregisterStreamFrameCallbackHandler(correlationId);
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        };

        // a thread that repeatedly iterates clientRegistry via reset() - the one legitimate
        // iteration path - while the mutators churn the callback registries
        Runnable resetter = () -> {
            ready.countDown();
            try {
                go.await();
                for (int i = 0; i < ITERATIONS / 4 && !stop.get(); i++) {
                    registry.reset();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        };

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            // most threads are mutators; a couple are resetters to drive iteration
            threads[i] = new Thread(i % 8 == 0 ? resetter : mutator, "ws-registry-stress-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }

        // wait for all threads to be ready, then release them simultaneously
        assertThat("all stress threads started", ready.await(30, TimeUnit.SECONDS), is(true));
        go.countDown();

        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            if (thread.isAlive()) {
                // a still-running thread after this long indicates a corrupted map / infinite loop
                stop.set(true);
                failures.add(new AssertionError("thread did not terminate (possible map corruption / infinite loop): " + thread.getName()));
            }
        }

        assertThat("no exceptions during concurrent access: " + failures, failures, is(empty()));

        // after a final quiescent reset the registry must be fully drained and usable
        registry.reset();
        assertThat("registry drained after reset", registry.size(), is(0));

        // and it must still function correctly after the stress run
        final boolean[] handled = {false};
        registry.registerResponseCallbackHandler("post-stress", response -> handled[0] = true);
        registry.receivedTextWebSocketFrame(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(
            new org.mockserver.serialization.WebSocketMessageSerializer(new MockServerLogger()).serialize(
                HttpResponse.response().withHeader(WebSocketClientRegistry.WEB_SOCKET_CORRELATION_ID_HEADER_NAME, "post-stress")
            )
        ));
        assertThat("callback still dispatched after stress run", handled[0], is(true));
    }
}
