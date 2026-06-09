package org.mockserver.state.infinispan;

import org.infinispan.commons.CacheException;
import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.state.ExpectationEntry;

import java.io.*;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies two security-relevant properties of the Infinispan state backend:
 * <ol>
 *   <li>{@link ExpectationEntry}'s custom {@code writeObject}/{@code readObject}
 *       correctly round-trips through Java serialization, serializing the
 *       non-{@link Serializable} {@link Expectation} as JSON.</li>
 *   <li>The clustered-mode allow-list (the regex patterns in
 *       {@link InfinispanStateBackend#createClusteredCacheManager}) accepts
 *       MockServer domain types and REJECTS types outside the allow-list,
 *       preventing deserialization gadget chains.</li>
 * </ol>
 */
class DeserializationAllowListTest {

    /**
     * Tests that {@link ExpectationEntry}'s custom writeObject/readObject
     * path correctly serializes the non-Serializable {@link Expectation}
     * as JSON and reconstructs it on deserialization.
     */
    @Nested
    @DisplayName("ExpectationEntry writeObject/readObject round-trip")
    class ExpectationEntrySerializationTest {

        @Test
        void shouldRoundTripExpectationEntryThroughJavaSerialization() throws Exception {
            Expectation expectation = new Expectation(HttpRequest.request("/test"))
                .withId("test-id-1")
                .thenRespond(org.mockserver.model.HttpResponse.response("OK"));
            ExpectationEntry original = new ExpectationEntry(expectation);

            // Serialize
            byte[] bytes;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(original);
                oos.flush();
                bytes = baos.toByteArray();
            }

            assertThat("serialized bytes should not be empty", bytes.length, greaterThan(0));

            // Deserialize
            ExpectationEntry deserialized;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 ObjectInputStream ois = new ObjectInputStream(bais)) {
                deserialized = (ExpectationEntry) ois.readObject();
            }

            // Verify structural fields round-trip
            assertThat(deserialized.getId(), is(original.getId()));
            assertThat(deserialized.getPriority(), is(original.getPriority()));
            assertThat(deserialized.getCreated(), is(original.getCreated()));
            assertThat(deserialized.getRemainingTimes(), is(original.getRemainingTimes()));

            // Verify the transient Expectation was reconstructed from JSON
            assertNotNull(deserialized.getExpectation(),
                "Expectation should be reconstructed from JSON via readObject");
            assertThat(deserialized.getExpectation().getHttpRequest(),
                is(notNullValue()));
        }

        @Test
        void shouldRoundTripExpectationEntryWithLimitedTimes() throws Exception {
            Expectation expectation = new Expectation(
                HttpRequest.request("/limited"),
                org.mockserver.matchers.Times.exactly(5),
                org.mockserver.matchers.TimeToLive.unlimited(),
                10
            ).withId("limited-id");
            ExpectationEntry original = new ExpectationEntry(expectation);

            assertThat("remainingTimes should be 5", original.getRemainingTimes(), is(5));

            byte[] bytes = serializeToBytes(original);
            ExpectationEntry deserialized = deserializeFromBytes(bytes);

            assertThat(deserialized.getId(), is("limited-id"));
            assertThat(deserialized.getPriority(), is(10));
            assertThat(deserialized.getRemainingTimes(), is(5));
            assertFalse(deserialized.isTimesUnlimited());
        }

        @Test
        void shouldRoundTripExpectationEntryWithUnlimitedTimes() throws Exception {
            Expectation expectation = new Expectation(HttpRequest.request("/unlimited"))
                .withId("unlimited-id");
            ExpectationEntry original = new ExpectationEntry(expectation);

            assertThat("remainingTimes should be -1 for unlimited",
                original.getRemainingTimes(), is(-1));

            byte[] bytes = serializeToBytes(original);
            ExpectationEntry deserialized = deserializeFromBytes(bytes);

            assertThat(deserialized.getRemainingTimes(), is(-1));
            assertTrue(deserialized.isTimesUnlimited());
        }

        private byte[] serializeToBytes(Object obj) throws IOException {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(obj);
                oos.flush();
                return baos.toByteArray();
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T deserializeFromBytes(byte[] bytes) throws IOException, ClassNotFoundException {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 ObjectInputStream ois = new ObjectInputStream(bais)) {
                return (T) ois.readObject();
            }
        }
    }

    /**
     * Tests that the Infinispan clustered-mode deserialization allow-list
     * (the same regex patterns used in
     * {@code InfinispanStateBackend.createClusteredCacheManager()})
     * correctly accepts MockServer domain types and rejects types outside
     * the allow-list. This proves the gadget-chain mitigation works.
     * <p>
     * Uses Infinispan's {@link ClassAllowList} and
     * {@link JavaSerializationMarshaller} directly to test the marshalling
     * layer without requiring a full JGroups transport.
     */
    @Nested
    @DisplayName("Clustered allow-list enforcement")
    class AllowListEnforcementTest {

        private ClassAllowList allowList;
        private JavaSerializationMarshaller marshaller;

        @BeforeEach
        void setUp() {
            // Use the EXACT allow-list the production marshaller applies, so this security
            // test cannot silently drift from createClusteredCacheManager().
            allowList = new ClassAllowList(
                Collections.emptySet(),
                InfinispanStateBackend.CLUSTERED_ALLOW_LIST_PATTERNS,
                null
            );
            marshaller = new JavaSerializationMarshaller(allowList);
        }

        // --- Allowed types pass the allow-list ---

        @Test
        void shouldAllowVersionedWrapper() {
            assertTrue(allowList.isSafeClass(VersionedWrapper.class.getName()),
                "VersionedWrapper should be on the allow-list");
        }

        @Test
        void shouldAllowExpectationEntry() {
            assertTrue(allowList.isSafeClass(ExpectationEntry.class.getName()),
                "ExpectationEntry should be on the allow-list");
        }

        @Test
        void shouldAllowExpectation() {
            assertTrue(allowList.isSafeClass(Expectation.class.getName()),
                "Expectation should be on the allow-list");
        }

        @Test
        void shouldAllowHttpRequest() {
            assertTrue(allowList.isSafeClass(HttpRequest.class.getName()),
                "HttpRequest should be on the allow-list");
        }

        @Test
        void shouldAllowJacksonObjectNode() {
            assertTrue(allowList.isSafeClass(
                    "com.fasterxml.jackson.databind.node.ObjectNode"),
                "Jackson ObjectNode should be on the allow-list");
        }

        @Test
        void shouldAllowJavaLangString() {
            assertTrue(allowList.isSafeClass("java.lang.String"),
                "java.lang.String should be on the allow-list");
        }

        @Test
        void shouldAllowJavaUtilHashMap() {
            assertTrue(allowList.isSafeClass("java.util.HashMap"),
                "java.util.HashMap should be on the allow-list");
        }

        @Test
        void shouldAllowByteArray() {
            assertTrue(allowList.isSafeClass("[B"),
                "byte[] should be on the allow-list");
        }

        // --- Disallowed types are rejected ---

        @Test
        void shouldRejectApacheCommonsCollections() {
            assertFalse(allowList.isSafeClass(
                    "org.apache.commons.collections4.functors.InvokerTransformer"),
                "Apache Commons Collections gadget class must be rejected");
        }

        @Test
        void shouldRejectSpringFrameworkTypes() {
            assertFalse(allowList.isSafeClass(
                    "org.springframework.beans.factory.config.MethodInvokingBean"),
                "Spring Framework types must be rejected");
        }

        @Test
        void shouldRejectRandomUnknownGadgetClass() {
            assertFalse(allowList.isSafeClass(
                    "com.evil.GadgetChainEntry"),
                "arbitrary unknown classes must be rejected");
        }

        @Test
        void shouldRejectJavaxNamingContext() {
            assertFalse(allowList.isSafeClass(
                    "javax.naming.InitialContext"),
                "javax.naming types (JNDI injection vector) must be rejected");
        }

        @Test
        void shouldRejectJavaRmiTypes() {
            assertFalse(allowList.isSafeClass(
                    "java.rmi.server.RemoteObject"),
                "java.rmi types must be rejected");
        }

        // --- Full marshaller round-trip with allow-list ---

        @Test
        void shouldMarshalAndUnmarshalAllowedType() throws Exception {
            VersionedWrapper<String> wrapper = new VersionedWrapper<>("hello", 1L);
            byte[] bytes = marshaller.objectToByteBuffer(wrapper);
            assertThat(bytes.length, greaterThan(0));

            @SuppressWarnings("unchecked")
            VersionedWrapper<String> restored =
                (VersionedWrapper<String>) marshaller.objectFromByteBuffer(bytes);
            assertThat(restored.getValue(), is("hello"));
            assertThat(restored.getVersion(), is(1L));
        }

        @Test
        void shouldRejectDisallowedTypeDuringDeserialization() throws Exception {
            // Serialize a java.net.URL (which IS Serializable but is in
            // java.net — a package NOT on the clustered allow-list) using a
            // PERMISSIVE marshaller that accepts everything for writing.
            JavaSerializationMarshaller permissiveMarshaller =
                new JavaSerializationMarshaller(new ClassAllowList(
                    Collections.emptySet(),
                    List.of(".*"),  // accept everything for serialization
                    null
                ));
            java.net.URL disallowed = new java.net.URL("http://example.com");
            byte[] bytes = permissiveMarshaller.objectToByteBuffer(disallowed);

            // Now attempt to deserialize using the TIGHT allow-list marshaller
            // — this should throw because java.net.URL is not in any of the
            // allowed packages (java.lang, java.util, java.time only)
            Exception thrown = assertThrows(CacheException.class, () ->
                marshaller.objectFromByteBuffer(bytes)
            );
            assertThat(thrown.getMessage(), containsString("allow"));
        }
    }
}
