package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Phase 2e acceptance test: starts TWO {@link InfinispanStateBackend}
 * instances clustered via in-JVM JGroups (loopback, REPL_SYNC), then
 * asserts cross-node visibility for expectations, scenario states, and
 * CRUD entities.
 * <p>
 * <b>Design:</b>
 * <ul>
 *   <li>Both nodes use the same clusterName and the built-in
 *       {@code jgroups-loopback.xml} stack (TCP/MPING on loopback,
 *       TTL=0, bind_port=0 for automatic port assignment).</li>
 *   <li>Cluster formation is awaited deterministically by polling
 *       the JGroups view size with a timeout — no fixed sleep.</li>
 *   <li>Propagation of writes is synchronous (REPL_SYNC), so once
 *       a put returns on node A, the value is visible on node B
 *       without additional waiting.</li>
 * </ul>
 * <p>
 * <b>No Docker, no external service.</b> This test runs in a single
 * JVM and can execute in CI as a regular unit test.
 */
@Timeout(60) // safety net: kill the test if cluster formation hangs
class ClusteredTwoNodeTest {

    private static final int MAX_EXPECTATIONS = 100;
    private static final Duration CLUSTER_FORMATION_TIMEOUT = Duration.ofSeconds(30);

    // Unique per test method: a static name reused across @BeforeEach/@AfterEach cycles
    // races JGroups SHARED_LOOPBACK channel teardown, making sibling tests flaky.
    private String clusterName;
    private InfinispanStateBackend nodeA;
    private InfinispanStateBackend nodeB;

    @BeforeEach
    void setUp() throws Exception {
        clusterName = "mockserver-test-cluster-" + System.nanoTime();
        // Start node A
        Configuration configA = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
        nodeA = new InfinispanStateBackend(configA);

        // Start node B
        Configuration configB = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
        nodeB = new InfinispanStateBackend(configB);

        // Wait for the 2-node cluster to form
        awaitClusterSize(nodeA, 2, CLUSTER_FORMATION_TIMEOUT);
        awaitClusterSize(nodeB, 2, CLUSTER_FORMATION_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    // --- Cross-node visibility: Expectations ---

    @Test
    void expectationWrittenOnNodeAShouldBeVisibleOnNodeB() {
        // Given: an expectation put on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/test-path")
        ).thenRespond(HttpResponse.response("OK"));

        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));

        // Then: visible on node B (REPL_SYNC = synchronous replication)
        Optional<Versioned<ExpectationEntry>> result = nodeB.expectations().get(expectation.getId());
        assertTrue(result.isPresent(), "expectation should be visible on node B");
        assertThat(result.get().getValue().getId(), is(expectation.getId()));
        assertThat(result.get().getValue().getExpectation().getHttpRequest(),
            is(expectation.getHttpRequest()));
    }

    @Test
    void expectationRemovedOnNodeBShouldDisappearFromNodeA() {
        // Given: an expectation on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/to-remove")
        ).thenRespond(HttpResponse.response("gone"));

        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));
        assertTrue(nodeB.expectations().get(expectation.getId()).isPresent());

        // When: removed on node B
        nodeB.expectations().remove(expectation.getId());

        // Then: gone from node A
        assertFalse(nodeA.expectations().get(expectation.getId()).isPresent(),
            "removed expectation should disappear from node A");
    }

    @Test
    void expectationUpdatedOnNodeAShouldReflectOnNodeB() {
        Expectation original = Expectation.when(
            HttpRequest.request("/update-test")
        ).thenRespond(HttpResponse.response("v1"));

        long v1 = nodeA.expectations().put(original.getId(), new ExpectationEntry(original));

        // Update the expectation on node A
        Expectation updated = Expectation.when(
            HttpRequest.request("/update-test")
        ).thenRespond(HttpResponse.response("v2"));
        // Use same ID for an update
        updated.withId(original.getId());
        updated.withCreated(original.getCreated());

        long v2 = nodeA.expectations().put(updated.getId(), new ExpectationEntry(updated));
        assertThat(v2, greaterThan(v1));

        // Should see updated version on node B
        Optional<Versioned<ExpectationEntry>> result = nodeB.expectations().get(updated.getId());
        assertTrue(result.isPresent());
        assertThat(result.get().getVersion(), is(v2));
    }

    // --- Cross-node visibility: Scenario States ---

    @Test
    void scenarioStateWrittenOnNodeAShouldBeVisibleOnNodeB() {
        nodeA.scenarioStates().put("scenario1", "Started");

        Optional<Versioned<String>> result = nodeB.scenarioStates().get("scenario1");
        assertTrue(result.isPresent(), "scenario state should be visible on node B");
        assertThat(result.get().getValue(), is("Started"));
    }

    @Test
    void scenarioStateCASOnNodeAShouldBeVisibleOnNodeB() {
        long v1 = nodeA.scenarioStates().put("cas-scenario", "Started");

        boolean success = nodeA.scenarioStates().compareAndSet("cas-scenario", v1, "Step1");
        assertTrue(success);

        Optional<Versioned<String>> result = nodeB.scenarioStates().get("cas-scenario");
        assertTrue(result.isPresent());
        assertThat(result.get().getValue(), is("Step1"));
    }

    // --- Cross-node visibility: CRUD Entities ---

    @Test
    void crudEntityWrittenOnNodeAShouldBeVisibleOnNodeB() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode entity = mapper.createObjectNode().put("name", "test-entity").put("value", 42);

        nodeA.crudEntities("testNs").put("entity1", entity);

        Optional<Versioned<ObjectNode>> result = nodeB.crudEntities("testNs").get("entity1");
        assertTrue(result.isPresent(), "CRUD entity should be visible on node B");
        assertThat(result.get().getValue().get("name").asText(), is("test-entity"));
        assertThat(result.get().getValue().get("value").asInt(), is(42));
    }

    // --- Cross-node invalidation listener ---

    @Test
    void invalidationListenerOnNodeBShouldFireWhenNodeAPutsExpectation() {
        AtomicInteger invalidationCount = new AtomicInteger(0);
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                invalidationCount.incrementAndGet();
            }

            @Override
            public void onCleared() {
            }
        });

        // Write on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/invalidation-test")
        ).thenRespond(HttpResponse.response("OK"));
        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));

        // The clustered listener (clustered=true) fires for remote writes.
        // REPL_SYNC ensures the put has completed on both nodes, but the
        // listener may fire asynchronously. Poll briefly.
        awaitCondition(() -> invalidationCount.get() > 0, Duration.ofSeconds(5),
            "invalidation listener on node B should have fired");
    }

    // --- Cluster properties ---

    @Test
    void bothNodesShouldBeClustered() {
        assertTrue(nodeA.isClustered());
        assertTrue(nodeB.isClustered());
    }

    @Test
    void nodesShouldHaveDifferentIds() {
        assertNotEquals(nodeA.nodeId(), nodeB.nodeId());
    }

    @Test
    void clearOnNodeAShouldClearNodeB() {
        nodeA.scenarioStates().put("s1", "v1");
        nodeA.scenarioStates().put("s2", "v2");
        assertTrue(nodeB.scenarioStates().get("s1").isPresent());
        assertTrue(nodeB.scenarioStates().get("s2").isPresent());

        nodeA.scenarioStates().clear();

        assertFalse(nodeB.scenarioStates().get("s1").isPresent());
        assertFalse(nodeB.scenarioStates().get("s2").isPresent());
        assertThat(nodeB.scenarioStates().size(), is(0));
    }

    @Test
    void entriesStreamOnNodeBShouldIncludeNodeAWrites() {
        nodeA.scenarioStates().put("a", "1");
        nodeA.scenarioStates().put("b", "2");
        nodeA.scenarioStates().put("c", "3");

        long countOnB = nodeB.scenarioStates().entries().count();
        assertThat(countOnB, is(3L));
    }

    // --- Cross-node MATCHABILITY (end-to-end through RequestMatchers) ---

    /**
     * End-to-end test: creates an expectation on node A, wires a
     * {@link RequestMatchers} to node B's backend with the
     * InvalidationListener triggering reconcileFromBackend, then asserts
     * that node B's RequestMatchers.peekFirstMatchingExpectation returns
     * the match. This proves cross-node MATCHABILITY, not just KV presence.
     */
    @Test
    void expectationCreatedOnNodeAShouldBeMatchableOnNodeBViaRequestMatchers() {
        // Wire a RequestMatchers to node B's backend, mirroring HttpState's wiring
        Configuration configB = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS);
        RequestMatchers nodeBMatchers = new RequestMatchers(
            configB, new MockServerLogger(),
            mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        nodeBMatchers.setStateBackend(nodeB);

        // Wire the invalidation listener (same as HttpState does)
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                nodeBMatchers.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                nodeBMatchers.reconcileFromBackend();
            }
        });

        // Create expectation on node A
        Expectation expectation = Expectation.when(
            HttpRequest.request("/cross-node-match")
        ).thenRespond(HttpResponse.response("cross-node-ok"));
        nodeA.expectations().put(expectation.getId(), new ExpectationEntry(expectation));

        // The clustered listener fires asynchronously for remote writes.
        // REPL_SYNC ensures the put has completed on both nodes, but the
        // listener callback may be slightly delayed. Poll briefly.
        awaitCondition(
            () -> nodeBMatchers.peekFirstMatchingExpectation(
                HttpRequest.request("/cross-node-match")) != null,
            Duration.ofSeconds(5),
            "node B's RequestMatchers should match the expectation created on node A"
        );

        // Verify the matched expectation is correct
        Expectation matched = nodeBMatchers.peekFirstMatchingExpectation(
            HttpRequest.request("/cross-node-match"));
        assertThat(matched, is(notNullValue()));
        assertThat(matched.getHttpResponse().getBodyAsString(), is("cross-node-ok"));
    }

    // --- Helpers ---

    /**
     * Polls the JGroups cluster view size until it reaches the expected
     * count, or throws if the timeout is exceeded. Deterministic — no
     * fixed sleep, only a brief poll interval.
     */
    private static void awaitClusterSize(InfinispanStateBackend backend, int expectedSize, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            int currentSize = backend.getCacheManager()
                .getTransport()
                .getMembers()
                .size();
            if (currentSize >= expectedSize) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for cluster formation", e);
            }
        }
        int finalSize = backend.getCacheManager().getTransport().getMembers().size();
        fail("cluster did not reach size " + expectedSize + " within " + timeout
            + "; current size=" + finalSize);
    }

    /**
     * Polls a condition with a short interval until it becomes true or
     * the timeout expires. For awaiting asynchronous invalidation events.
     */
    private static void awaitCondition(java.util.function.BooleanSupplier condition,
                                       Duration timeout, String message) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted: " + message, e);
            }
        }
        fail(message + " (timed out after " + timeout + ")");
    }
}
