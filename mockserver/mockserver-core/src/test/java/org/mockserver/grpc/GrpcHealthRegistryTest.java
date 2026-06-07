package org.mockserver.grpc;

import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Covers {@link GrpcHealthRegistry#removeStatus(String)} — the per-service override removal that
 * backs the dashboard's gRPC health Reset button (previously the endpoint had no remove path and
 * returned 400, so Reset never worked).
 */
public class GrpcHealthRegistryTest {

    private final GrpcHealthRegistry registry = GrpcHealthRegistry.getInstance();

    @After
    public void tearDown() {
        registry.reset();
    }

    @Test
    public void removeStatusRevertsServiceToDefault() {
        registry.setStatus("orders.v1.OrderService", ServingStatus.NOT_SERVING);
        assertThat(registry.getStatus("orders.v1.OrderService"), is(ServingStatus.NOT_SERVING));

        registry.removeStatus("orders.v1.OrderService");

        // reverts to the (SERVING) default rather than persisting the override
        assertThat(registry.getStatus("orders.v1.OrderService"), is(ServingStatus.SERVING));
    }

    @Test
    public void removeStatusWithEmptyServiceResetsTheDefault() {
        registry.setStatus("", ServingStatus.NOT_SERVING);
        assertThat(registry.getStatus("anything"), is(ServingStatus.NOT_SERVING));

        registry.removeStatus("");

        assertThat(registry.getStatus("anything"), is(ServingStatus.SERVING));
    }

    @Test
    public void removeStatusForUnknownServiceIsANoOp() {
        registry.removeStatus("never.registered");
        assertThat(registry.getStatus("never.registered"), is(ServingStatus.SERVING));
    }

    @Test
    public void removeStatusHandlesNullSafely() {
        registry.removeStatus(null);
        assertThat(registry.getStatus("x"), is(ServingStatus.SERVING));
    }
}
