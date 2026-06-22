package org.mockserver.grpc;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Verifies {@link GrpcProtoDescriptorStore#synthesizeResponseJson(String, String)} against a
 * real compiled descriptor set ({@code greeting.dsc}). This is the entry point the gRPC
 * response path uses to synthesize a schema-valid response when no body was hand-authored.
 */
public class GrpcProtoDescriptorStoreSynthesisTest {

    private GrpcProtoDescriptorStore store;

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("src/test/resources/grpc/greeting.dsc"));
    }

    @Test
    public void shouldSynthesizeResponseForKnownMethod() {
        String json = store.synthesizeResponseJson("com.example.grpc.GreetingService", "Greeting");

        // HelloResponse has a single string field "greeting"
        assertThat(json, containsString("\"greeting\""));
        assertThat(json, containsString("string"));

        // the synthesized JSON must round-trip back into a valid protobuf message
        byte[] protobuf = store.getConverter().toProtobuf(
            json, store.getMethod("com.example.grpc.GreetingService", "Greeting").getOutputType());
        assertThat(protobuf, is(notNullValue()));
    }

    @Test
    public void shouldReturnNullForUnknownMethod() {
        assertThat(store.synthesizeResponseJson("com.example.grpc.GreetingService", "DoesNotExist"), is(nullValue()));
        assertThat(store.synthesizeResponseJson("no.such.Service", "Greeting"), is(nullValue()));
    }

    @Test
    public void shouldExposeSharedSynthesizerInstance() {
        assertThat(store.getExampleSynthesizer(), is(notNullValue()));
        assertThat(store.getExampleSynthesizer(), is(sameInstance(store.getExampleSynthesizer())));
    }
}
