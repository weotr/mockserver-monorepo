package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MatchType;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.JsonBody;
import org.mockserver.model.MediaType;
import org.mockserver.model.RequestDefinition;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for GitHub issue #2374:
 * {@code HttpRequest.getBodyAsOriginalRawBytes()} returned pretty-printed JSON instead of the exact
 * wire bytes for {@code application/json} requests retrieved via {@code retrieveRecordedRequests}.
 * <p>
 * The original raw bytes are lost on round-trip unless the JSON body serializer emits a {@code rawBytes}
 * field whenever the wire bytes cannot be reconstructed from the (re-serialised) JSON value.
 */
public class JsonBodyRawBytesRoundTripTest {

    // the exact 16 wire bytes of an incoming application/json request body, with original spacing
    private static final String ORIGINAL_STRING = "{ \"foo\": \"bar\" }";
    private static final byte[] ORIGINAL_BYTES = ORIGINAL_STRING.getBytes(StandardCharsets.UTF_8);

    private static HttpRequest recordedJsonRequest() {
        // mirrors how BodyDecoderEncoder builds a recorded request body: json value == verbatim wire string,
        // rawBytes == the exact wire bytes, default content type (application/json) and default match type
        return new HttpRequest().withBody(
            new JsonBody(ORIGINAL_STRING, ORIGINAL_BYTES, MediaType.APPLICATION_JSON_UTF_8, MatchType.ONLY_MATCHING_FIELDS)
        );
    }

    @Test
    public void originalBytesAreSixteenBeforeSerialization() {
        assertThat(ORIGINAL_BYTES.length, is(16));
    }

    @Test
    public void shouldRoundTripOriginalRawBytesViaRequestDefinitionSerializerPrettyPrinted() {
        // this is the exact path used by retrieveRecordedRequests (prettyPrint = true -> HttpRequestPrettyPrintedDTO);
        // only serializeRecordedRequests emits rawBytes, so the original bytes survive the round-trip
        RequestDefinitionSerializer serializer = new RequestDefinitionSerializer(new MockServerLogger());

        String serialized = serializer.serializeRecordedRequests(true, java.util.Collections.singletonList(recordedJsonRequest()));

        // the serialized form must carry the rawBytes so the original bytes survive the round-trip
        assertTrue("expected rawBytes field in serialized recorded request but was: " + serialized, serialized.contains("rawBytes"));

        RequestDefinition deserialized = serializer.deserializeArray(serialized)[0];
        assertTrue(deserialized instanceof HttpRequest);
        HttpRequest result = (HttpRequest) deserialized;

        assertArrayEquals(ORIGINAL_BYTES, result.getBodyAsRawBytes());
        assertArrayEquals(ORIGINAL_BYTES, result.getBodyAsOriginalRawBytes());
    }

    @Test
    public void shouldRoundTripOriginalRawBytesViaRequestDefinitionSerializerCompact() {
        RequestDefinitionSerializer serializer = new RequestDefinitionSerializer(new MockServerLogger());

        String serialized = serializer.serializeRecordedRequests(false, java.util.Collections.singletonList(recordedJsonRequest()));

        assertTrue("expected rawBytes field in serialized recorded request but was: " + serialized, serialized.contains("rawBytes"));

        RequestDefinition deserialized = serializer.deserializeArray(serialized)[0];
        assertTrue(deserialized instanceof HttpRequest);
        HttpRequest result = (HttpRequest) deserialized;

        assertArrayEquals(ORIGINAL_BYTES, result.getBodyAsRawBytes());
        assertArrayEquals(ORIGINAL_BYTES, result.getBodyAsOriginalRawBytes());
    }

    @Test
    public void shouldNotEmitRawBytesWhenWireBytesMatchCanonicalSerialization() {
        // a body whose wire bytes already equal the canonical (compact) serialisation of the parsed tree carries
        // no extra information, so no rawBytes field is emitted - this keeps canonical user-authored bodies and
        // existing golden output untouched (no output bloat)
        String canonical = "{\"foo\":\"bar\"}";
        HttpRequest request = new HttpRequest().withBody(
            new JsonBody(canonical, canonical.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON_UTF_8, MatchType.ONLY_MATCHING_FIELDS)
        );

        RequestDefinitionSerializer serializer = new RequestDefinitionSerializer(new MockServerLogger());
        String serialized = serializer.serializeRecordedRequests(false, java.util.Collections.singletonList(request));

        assertThat("canonical body should not carry rawBytes but was: " + serialized, serialized.contains("rawBytes"), is(false));
    }

    @Test
    public void shouldNotEmitRawBytesViaGenericSerializeOutsideRetrievalPath() {
        // the generic serialize(...) overloads (used for matcher/expectation serialisation and diagnostic logs) must
        // stay clean - they never emit rawBytes even when the wire bytes differ from the canonical serialisation
        RequestDefinitionSerializer serializer = new RequestDefinitionSerializer(new MockServerLogger());

        String serialized = serializer.serialize(true, recordedJsonRequest());

        assertThat("matcher/diagnostic serialisation should not carry rawBytes but was: " + serialized, serialized.contains("rawBytes"), is(false));
    }
}
