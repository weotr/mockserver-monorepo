package org.mockserver.netty.grpc;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.GrpcChaosProfile;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;

/**
 * Handler-level coverage for gRPC fault injection in {@link GrpcToHttpRequestHandler}.
 *
 * <p>The registry / decision / profile classes are unit-tested in isolation; this test
 * exercises the actual <em>wiring</em> through the Netty handler via an {@link EmbeddedChannel}
 * — i.e. that a registered gRPC chaos profile causes the handler to short-circuit a matched
 * gRPC request with a {@code grpc-status} error response, and that absent/zero-probability
 * chaos passes the request through untouched. (A bug in exactly this kind of handler wiring
 * — present but never integration-tested — is what previously let mock-drift detection ship
 * broken; this guards the gRPC-chaos equivalent.)
 */
public class GrpcToHttpRequestHandlerChaosTest {

    private final GrpcChaosRegistry chaosRegistry = new GrpcChaosRegistry(System::currentTimeMillis);

    private EmbeddedChannel channelWith(GrpcChaosRegistry registry) {
        GrpcToHttpRequestHandler handler = new GrpcToHttpRequestHandler(
            new MockServerLogger(),
            new GrpcProtoDescriptorStore(new MockServerLogger()),
            new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            registry,
            HttpQuotaRegistry.getInstance());
        return new EmbeddedChannel(handler);
    }

    private HttpRequest grpcRequest(String path) {
        return request().withMethod("POST").withPath(path)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(new byte[]{0, 0, 0, 0, 0});
    }

    @After
    public void tearDown() {
        chaosRegistry.reset();
    }

    @Test
    public void injectsConfiguredGrpcStatusWhenProbabilityIsOne() {
        chaosRegistry.put("pay.v1.PaymentService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/pay.v1.PaymentService/Charge"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("14")); // UNAVAILABLE
        // request must NOT be propagated to normal handling when chaos fires
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void injectsCustomStatusCode() {
        chaosRegistry.put("orders.v1.OrderService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("DEADLINE_EXCEEDED").withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/orders.v1.OrderService/Create"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("4")); // DEADLINE_EXCEEDED
    }

    @Test
    public void passesThroughWhenNoChaosRegistered() {
        EmbeddedChannel channel = channelWith(chaosRegistry);

        HttpRequest req = grpcRequest("/free.v1.FreeService/Call");
        channel.writeInbound(req);

        // no chaos and no descriptor → request flows through to normal handling
        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }

    @Test
    public void passesThroughWhenProbabilityIsZero() {
        chaosRegistry.put("quiet.v1.QuietService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(0.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/quiet.v1.QuietService/Call"));

        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }

    @Test
    public void nonGrpcRequestIsUnaffectedByChaos() {
        chaosRegistry.put("pay.v1.PaymentService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // a plain (non-gRPC) request must never be touched by gRPC chaos
        channel.writeInbound(request().withMethod("POST").withPath("/pay.v1.PaymentService/Charge")
            .withHeader("content-type", "application/json"));

        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }
}
