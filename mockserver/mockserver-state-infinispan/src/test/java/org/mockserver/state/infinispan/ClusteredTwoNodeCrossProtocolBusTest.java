package org.mockserver.state.infinispan;

import org.junit.jupiter.api.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.ScenarioManager;
import org.mockserver.model.CrossProtocolScenario;
import org.mockserver.model.CrossProtocolTrigger;
import org.mockserver.state.InvalidationListener;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G11 follow-up acceptance test: starts TWO {@link InfinispanStateBackend}
 * instances clustered via in-JVM JGroups (loopback, REPL_SYNC), then asserts
 * that cross-protocol event bus registrations on node A become visible and
 * effective on node B after invalidation-driven reconciliation.
 * <p>
 * This test validates:
 * <ul>
 *   <li>Registrations replicate across nodes</li>
 *   <li>A trigger fired on node B (after receiving a registration from A) fires the expected scenario state transition</li>
 *   <li>Unregister on one node removes the registration from the other</li>
 *   <li>Reset on one node clears registrations on the other</li>
 *   <li>Multiple triggers/patterns replicate independently</li>
 * </ul>
 * <p>
 * <b>Non-singleton buses:</b> the test uses fresh per-test
 * {@link CrossProtocolEventBus} instances (not the static singleton) wired to
 * each node's backend. This avoids interference with singleton state from
 * other tests running in the same JVM.
 */
@Timeout(60)
class ClusteredTwoNodeCrossProtocolBusTest {

    private static final int MAX_EXPECTATIONS = 100;
    private static final Duration CLUSTER_FORMATION_TIMEOUT = Duration.ofSeconds(30);

    private String clusterName;
    private InfinispanStateBackend nodeA;
    private InfinispanStateBackend nodeB;

    @BeforeEach
    void setUp() throws Exception {
        clusterName = "cpbus-test-cluster-" + System.nanoTime();

        Configuration configA = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
        nodeA = new InfinispanStateBackend(configA);

        Configuration configB = Configuration.configuration()
            .maxExpectations(MAX_EXPECTATIONS)
            .stateBackend("infinispan")
            .clusterEnabled(true)
            .clusterName(clusterName);
        nodeB = new InfinispanStateBackend(configB);

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

    @Test
    void registrationOnNodeAShouldBeVisibleOnNodeB() {
        // Create per-test buses wired to each backend
        CrossProtocolEventBus busA = new CrossProtocolEventBus();
        CrossProtocolEventBus busB = new CrossProtocolEventBus();
        ScenarioManager scenarioB = new ScenarioManager();
        busA.setStateBackend(nodeA);
        busB.setStateBackend(nodeB);
        busB.setScenarioManager(scenarioB);

        // Wire invalidation listener on node B to reconcile the bus
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                busB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                busB.reconcileFromBackend();
            }
        });

        // Register a cross-protocol scenario on node A
        CrossProtocolScenario scenario = CrossProtocolScenario.onDnsQuery(
            "api.example.com", "DnsScenario", "DnsObserved"
        );
        busA.register(scenario);

        // Wait for node B to reconcile, then fire the trigger on node B
        awaitCondition(
            () -> {
                busB.fire(CrossProtocolTrigger.DNS_QUERY, "api.example.com");
                return "DnsObserved".equals(scenarioB.getState("DnsScenario"));
            },
            Duration.ofSeconds(5),
            "firing DNS_QUERY on node B should transition DnsScenario to DnsObserved"
        );

        assertThat(scenarioB.getState("DnsScenario"), is("DnsObserved"));
    }

    @Test
    void unregisterOnNodeBShouldRemoveFromNodeA() {
        CrossProtocolEventBus busA = new CrossProtocolEventBus();
        CrossProtocolEventBus busB = new CrossProtocolEventBus();
        ScenarioManager scenarioA = new ScenarioManager();
        busA.setStateBackend(nodeA);
        busB.setStateBackend(nodeB);
        busA.setScenarioManager(scenarioA);

        // Wire invalidation on both nodes
        nodeA.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                busA.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                busA.reconcileFromBackend();
            }
        });
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                busB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                busB.reconcileFromBackend();
            }
        });

        // Register on A
        CrossProtocolScenario scenario = CrossProtocolScenario.onHttpPath(
            "/api/", "ApiScenario", "ApiCalled"
        );
        busA.register(scenario);

        // Wait for B to see it by checking the backend store
        awaitCondition(
            () -> {
                // Fire on A to verify registration took
                busA.fire(CrossProtocolTrigger.HTTP_REQUEST, "/api/test");
                return "ApiCalled".equals(scenarioA.getState("ApiScenario"));
            },
            Duration.ofSeconds(5),
            "node A should have the registration active"
        );

        // Reset scenario state, then unregister on B
        scenarioA.setState("ApiScenario", ScenarioManager.STARTED);
        busB.unregister(scenario);

        // After B's unregister propagates, A should no longer fire
        awaitCondition(
            () -> {
                busA.reconcileFromBackend();
                // reset state then try firing
                scenarioA.setState("ApiScenario", ScenarioManager.STARTED);
                busA.fire(CrossProtocolTrigger.HTTP_REQUEST, "/api/test");
                return ScenarioManager.STARTED.equals(scenarioA.getState("ApiScenario"));
            },
            Duration.ofSeconds(5),
            "after unregister on node B, firing on node A should not transition the scenario"
        );
    }

    @Test
    void resetOnNodeAShouldClearNodeB() {
        CrossProtocolEventBus busA = new CrossProtocolEventBus();
        CrossProtocolEventBus busB = new CrossProtocolEventBus();
        ScenarioManager scenarioB = new ScenarioManager();
        busA.setStateBackend(nodeA);
        busB.setStateBackend(nodeB);
        busB.setScenarioManager(scenarioB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                busB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                busB.reconcileFromBackend();
            }
        });

        // Register two scenarios on A
        busA.register(CrossProtocolScenario.onDnsQuery("a.com", "S1", "State1"));
        busA.register(CrossProtocolScenario.onGrpcRequest("SvcX", "S2", "State2"));

        // Wait for B to see both
        awaitCondition(
            () -> {
                busB.fire(CrossProtocolTrigger.DNS_QUERY, "a.com");
                busB.fire(CrossProtocolTrigger.GRPC_REQUEST, "SvcX");
                return "State1".equals(scenarioB.getState("S1"))
                    && "State2".equals(scenarioB.getState("S2"));
            },
            Duration.ofSeconds(5),
            "node B should see both registrations"
        );

        // Reset on A should propagate to B
        busA.reset();

        // Reset scenario states on B and verify that firing no longer transitions
        awaitCondition(
            () -> {
                busB.reconcileFromBackend();
                scenarioB.setState("S1", ScenarioManager.STARTED);
                scenarioB.setState("S2", ScenarioManager.STARTED);
                busB.fire(CrossProtocolTrigger.DNS_QUERY, "a.com");
                busB.fire(CrossProtocolTrigger.GRPC_REQUEST, "SvcX");
                return ScenarioManager.STARTED.equals(scenarioB.getState("S1"))
                    && ScenarioManager.STARTED.equals(scenarioB.getState("S2"));
            },
            Duration.ofSeconds(5),
            "after reset on A, node B should have no registrations"
        );
    }

    @Test
    void multipleTriggerTypesReplicateIndependently() {
        CrossProtocolEventBus busA = new CrossProtocolEventBus();
        CrossProtocolEventBus busB = new CrossProtocolEventBus();
        ScenarioManager scenarioB = new ScenarioManager();
        busA.setStateBackend(nodeA);
        busB.setStateBackend(nodeB);
        busB.setScenarioManager(scenarioB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                busB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                busB.reconcileFromBackend();
            }
        });

        // Register one of each trigger type on A
        busA.register(CrossProtocolScenario.onDnsQuery("dns.example.com", "DnsSc", "DnsState"));
        busA.register(CrossProtocolScenario.onWebSocketConnect("WsSc", "WsState"));
        busA.register(CrossProtocolScenario.onGrpcRequest("UserSvc", "GrpcSc", "GrpcState"));
        busA.register(CrossProtocolScenario.onHttpPath("/v1/", "HttpSc", "HttpState"));

        // Fire all on B and verify
        awaitCondition(
            () -> {
                busB.fire(CrossProtocolTrigger.DNS_QUERY, "dns.example.com");
                busB.fire(CrossProtocolTrigger.WEBSOCKET_CONNECT, "/ws");
                busB.fire(CrossProtocolTrigger.GRPC_REQUEST, "/com.example.UserSvc/Get");
                busB.fire(CrossProtocolTrigger.HTTP_REQUEST, "/v1/users");
                return "DnsState".equals(scenarioB.getState("DnsSc"))
                    && "WsState".equals(scenarioB.getState("WsSc"))
                    && "GrpcState".equals(scenarioB.getState("GrpcSc"))
                    && "HttpState".equals(scenarioB.getState("HttpSc"));
            },
            Duration.ofSeconds(10),
            "all four trigger types should replicate to node B"
        );

        assertThat(scenarioB.getState("DnsSc"), is("DnsState"));
        assertThat(scenarioB.getState("WsSc"), is("WsState"));
        assertThat(scenarioB.getState("GrpcSc"), is("GrpcState"));
        assertThat(scenarioB.getState("HttpSc"), is("HttpState"));
    }

    // --- Helpers ---

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
