package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.model.GrpcChaosProfile;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.TcpChaosProfile.tcpChaosProfile;
import static org.mockserver.model.GrpcChaosProfile.grpcChaosProfile;

/**
 * G11 acceptance test: starts TWO {@link InfinispanStateBackend} instances
 * clustered via in-JVM JGroups (loopback, REPL_SYNC), then asserts that
 * chaos profiles registered on node A become visible on node B's chaos
 * registries after invalidation-driven reconciliation.
 * <p>
 * This test validates:
 * <ul>
 *   <li>Service chaos (HTTP) profiles replicate across nodes</li>
 *   <li>TCP chaos profiles replicate across nodes</li>
 *   <li>gRPC chaos profiles replicate across nodes</li>
 *   <li>Removal on one node propagates to the other</li>
 *   <li>Reset (clear all) on one node propagates to the other</li>
 * </ul>
 * <p>
 * <b>Non-singleton registries:</b> the test uses fresh per-test registry
 * instances (not the static singletons) with a controllable clock, wired
 * to each node's backend. This avoids interference with singleton state
 * from other tests running in the same JVM.
 */
@Timeout(60)
class ClusteredTwoNodeChaosTest {

    private static final int MAX_EXPECTATIONS = 100;
    private static final Duration CLUSTER_FORMATION_TIMEOUT = Duration.ofSeconds(30);

    private String clusterName;
    private InfinispanStateBackend nodeA;
    private InfinispanStateBackend nodeB;

    @BeforeEach
    void setUp() throws Exception {
        clusterName = "chaos-test-cluster-" + System.nanoTime();

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

    // --- Service (HTTP) chaos cross-node tests ---

    @Test
    void serviceChaosRegisteredOnNodeAShouldBeVisibleOnNodeB() {
        // Create per-test registries (not singletons) wired to each backend
        AtomicLong clock = new AtomicLong(1_000L);
        ServiceChaosRegistry registryA = new ServiceChaosRegistry(clock::get);
        ServiceChaosRegistry registryB = new ServiceChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        // Wire invalidation listener on node B so remote writes trigger reconcile
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        // Register a service chaos profile on node A
        HttpChaosProfile profile = httpChaosProfile()
            .withErrorStatus(503)
            .withErrorProbability(0.75);
        registryA.put("api.example.com", profile);

        // The backend write is synchronous (REPL_SYNC), but the clustered
        // listener callback may be slightly delayed. Poll briefly.
        awaitCondition(
            () -> registryB.get("api.example.com") != null,
            Duration.ofSeconds(5),
            "node B should see the service chaos profile registered on node A"
        );

        HttpChaosProfile resolved = registryB.get("api.example.com");
        assertThat(resolved, is(notNullValue()));
        assertThat(resolved.getErrorStatus(), is(503));
        assertThat(resolved.getErrorProbability(), is(0.75));
    }

    @Test
    void serviceChaosRemovedOnNodeBShouldDisappearFromNodeA() {
        AtomicLong clock = new AtomicLong(1_000L);
        ServiceChaosRegistry registryA = new ServiceChaosRegistry(clock::get);
        ServiceChaosRegistry registryB = new ServiceChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        // Wire invalidation on both nodes
        nodeA.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryA.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryA.reconcileFromBackend();
            }
        });
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        // Register on A, wait for B to see it
        registryA.put("api.example.com", httpChaosProfile().withErrorStatus(500));
        awaitCondition(
            () -> registryB.get("api.example.com") != null,
            Duration.ofSeconds(5),
            "node B should see the profile"
        );

        // Remove on B
        registryB.remove("api.example.com");

        // Should disappear from A
        awaitCondition(
            () -> registryA.get("api.example.com") == null,
            Duration.ofSeconds(5),
            "node A should no longer see the removed profile"
        );
    }

    @Test
    void serviceChaosResetOnNodeAShouldClearNodeB() {
        AtomicLong clock = new AtomicLong(1_000L);
        ServiceChaosRegistry registryA = new ServiceChaosRegistry(clock::get);
        ServiceChaosRegistry registryB = new ServiceChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        registryA.put("a.svc", httpChaosProfile().withErrorStatus(503));
        registryA.put("b.svc", httpChaosProfile().withErrorStatus(500));
        awaitCondition(
            () -> registryB.entries().size() == 2,
            Duration.ofSeconds(5),
            "node B should see both profiles"
        );

        // Reset on A should clear the backend, which should clear B
        registryA.reset();
        awaitCondition(
            () -> registryB.entries().isEmpty(),
            Duration.ofSeconds(5),
            "node B should be empty after reset on node A"
        );
    }

    // --- TCP chaos cross-node tests ---

    @Test
    void tcpChaosRegisteredOnNodeAShouldBeVisibleOnNodeB() {
        AtomicLong clock = new AtomicLong(1_000L);
        TcpChaosRegistry registryA = new TcpChaosRegistry(clock::get);
        TcpChaosRegistry registryB = new TcpChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        TcpChaosProfile profile = tcpChaosProfile()
            .withLatencyMs(500L)
            .withDown(false);
        registryA.put("db.internal", profile);

        awaitCondition(
            () -> registryB.get("db.internal") != null,
            Duration.ofSeconds(5),
            "node B should see the TCP chaos profile registered on node A"
        );

        TcpChaosProfile resolved = registryB.get("db.internal");
        assertThat(resolved, is(notNullValue()));
        assertThat(resolved.getLatencyMs(), is(500L));
        assertThat(resolved.getDown(), is(false));
    }

    @Test
    void tcpChaosRemovedOnNodeBShouldDisappearFromNodeA() {
        AtomicLong clock = new AtomicLong(1_000L);
        TcpChaosRegistry registryA = new TcpChaosRegistry(clock::get);
        TcpChaosRegistry registryB = new TcpChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        nodeA.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryA.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryA.reconcileFromBackend();
            }
        });
        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        registryA.put("db.internal", tcpChaosProfile().withResetPeer(true));
        awaitCondition(
            () -> registryB.get("db.internal") != null,
            Duration.ofSeconds(5),
            "node B should see the TCP profile"
        );

        registryB.remove("db.internal");

        awaitCondition(
            () -> registryA.get("db.internal") == null,
            Duration.ofSeconds(5),
            "node A should no longer see the removed TCP profile"
        );
    }

    // --- gRPC chaos cross-node tests ---

    @Test
    void grpcChaosRegisteredOnNodeAShouldBeVisibleOnNodeB() {
        AtomicLong clock = new AtomicLong(1_000L);
        GrpcChaosRegistry registryA = new GrpcChaosRegistry(clock::get);
        GrpcChaosRegistry registryB = new GrpcChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorStatusCode("UNAVAILABLE")
            .withErrorProbability(1.0);
        registryA.put("greeter.GreeterService", profile);

        awaitCondition(
            () -> registryB.get("greeter.greeterservice") != null,
            Duration.ofSeconds(5),
            "node B should see the gRPC chaos profile registered on node A"
        );

        GrpcChaosProfile resolved = registryB.get("greeter.GreeterService");
        assertThat(resolved, is(notNullValue()));
        assertThat(resolved.getErrorStatusCode(), is("UNAVAILABLE"));
        assertThat(resolved.getErrorProbability(), is(1.0));
    }

    @Test
    void grpcChaosDefaultProfileShouldReplicateAcrossNodes() {
        AtomicLong clock = new AtomicLong(1_000L);
        GrpcChaosRegistry registryA = new GrpcChaosRegistry(clock::get);
        GrpcChaosRegistry registryB = new GrpcChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        // Register default (empty-string key) profile on A
        GrpcChaosProfile defaultProfile = grpcChaosProfile()
            .withErrorStatusCode("INTERNAL")
            .withErrorProbability(0.5);
        registryA.put("", defaultProfile);

        // On node B, looking up an unregistered service should fall back to the default
        awaitCondition(
            () -> registryB.get("any.Service") != null,
            Duration.ofSeconds(5),
            "node B should see the default gRPC chaos profile via fallback"
        );

        GrpcChaosProfile resolved = registryB.get("any.Service");
        assertThat(resolved.getErrorStatusCode(), is("INTERNAL"));
        assertThat(resolved.getErrorProbability(), is(0.5));
    }

    @Test
    void grpcChaosRemovedOnNodeAShouldDisappearFromNodeB() {
        AtomicLong clock = new AtomicLong(1_000L);
        GrpcChaosRegistry registryA = new GrpcChaosRegistry(clock::get);
        GrpcChaosRegistry registryB = new GrpcChaosRegistry(clock::get);
        registryA.setStateBackend(nodeA);
        registryB.setStateBackend(nodeB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                registryB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                registryB.reconcileFromBackend();
            }
        });

        registryA.put("greeter.GreeterService", grpcChaosProfile()
            .withErrorStatusCode("DEADLINE_EXCEEDED")
            .withErrorProbability(1.0));

        awaitCondition(
            () -> registryB.get("greeter.greeterservice") != null,
            Duration.ofSeconds(5),
            "node B should see the gRPC profile"
        );

        registryA.remove("greeter.GreeterService");

        awaitCondition(
            () -> registryB.get("greeter.greeterservice") == null,
            Duration.ofSeconds(5),
            "node B should no longer see the removed gRPC profile"
        );
    }

    // --- Cross-protocol: all three types in one cluster ---

    @Test
    void allThreeChaosTypesReplicateIndependently() {
        AtomicLong clock = new AtomicLong(1_000L);
        ServiceChaosRegistry svcA = new ServiceChaosRegistry(clock::get);
        ServiceChaosRegistry svcB = new ServiceChaosRegistry(clock::get);
        TcpChaosRegistry tcpA = new TcpChaosRegistry(clock::get);
        TcpChaosRegistry tcpB = new TcpChaosRegistry(clock::get);
        GrpcChaosRegistry grpcA = new GrpcChaosRegistry(clock::get);
        GrpcChaosRegistry grpcB = new GrpcChaosRegistry(clock::get);

        svcA.setStateBackend(nodeA);
        svcB.setStateBackend(nodeB);
        tcpA.setStateBackend(nodeA);
        tcpB.setStateBackend(nodeB);
        grpcA.setStateBackend(nodeA);
        grpcB.setStateBackend(nodeB);

        nodeB.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                svcB.reconcileFromBackend();
                tcpB.reconcileFromBackend();
                grpcB.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                svcB.reconcileFromBackend();
                tcpB.reconcileFromBackend();
                grpcB.reconcileFromBackend();
            }
        });

        // Register one profile per type on node A
        svcA.put("web.svc", httpChaosProfile().withErrorStatus(503));
        tcpA.put("db.internal", tcpChaosProfile().withDown(true));
        grpcA.put("auth.AuthService", grpcChaosProfile()
            .withErrorStatusCode("UNAUTHENTICATED")
            .withErrorProbability(1.0));

        awaitCondition(
            () -> svcB.get("web.svc") != null
                && tcpB.get("db.internal") != null
                && grpcB.get("auth.authservice") != null,
            Duration.ofSeconds(10),
            "node B should see all three chaos types"
        );

        assertThat(svcB.get("web.svc").getErrorStatus(), is(503));
        assertThat(tcpB.get("db.internal").getDown(), is(true));
        assertThat(grpcB.get("auth.authservice").getErrorStatusCode(), is("UNAUTHENTICATED"));
    }

    // --- Verify backend uses correct CRUD namespaces ---

    @Test
    void chaosTypesUseDistinctBackendNamespaces() {
        // Verify the CRUD namespaces are distinct by checking that the backend
        // KV stores for each chaos type are different. We do this indirectly:
        // register a profile in each type and verify they don't collide.
        AtomicLong clock = new AtomicLong(1_000L);
        ServiceChaosRegistry svcA = new ServiceChaosRegistry(clock::get);
        TcpChaosRegistry tcpA = new TcpChaosRegistry(clock::get);
        GrpcChaosRegistry grpcA = new GrpcChaosRegistry(clock::get);
        svcA.setStateBackend(nodeA);
        tcpA.setStateBackend(nodeA);
        grpcA.setStateBackend(nodeA);

        // Use the same key across types
        svcA.put("shared-key", httpChaosProfile().withErrorStatus(503));
        tcpA.put("shared-key", tcpChaosProfile().withDown(true));
        grpcA.put("shared-key", grpcChaosProfile().withErrorStatusCode("INTERNAL").withErrorProbability(1.0));

        // Each registry should still see its own profile, not cross-contaminated
        assertThat(svcA.get("shared-key").getErrorStatus(), is(503));
        assertThat(tcpA.get("shared-key").getDown(), is(true));
        assertThat(grpcA.get("shared-key").getErrorStatusCode(), is("INTERNAL"));
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
