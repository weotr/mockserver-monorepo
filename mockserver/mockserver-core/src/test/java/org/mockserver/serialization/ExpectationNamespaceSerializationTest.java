package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies the {@code namespace} (multi-tenancy) field round-trips through JSON
 * serialization, and that omitting it (the default) leaves payloads unchanged.
 *
 * @author jamesdbloom
 */
public class ExpectationNamespaceSerializationTest {

    private final ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

    @Test
    public void shouldRoundTripNamespaceField() {
        // given
        Expectation expectation = new Expectation(request().withPath("/some/path"))
            .withNamespace("team-a")
            .thenRespond(response().withBody("someBody"));

        // when
        String json = serializer.serialize(expectation);
        Expectation deserialized = serializer.deserialize(json);

        // then
        assertThat(json, containsString("\"namespace\""));
        assertThat(json, containsString("team-a"));
        assertThat(deserialized.getNamespace(), is("team-a"));
    }

    @Test
    public void shouldDeserializeNamespaceFromLiteralJson() {
        // when
        Expectation deserialized = serializer.deserialize("{" +
            "  \"namespace\": \"team-b\"," +
            "  \"httpRequest\": { \"path\": \"/some/path\" }," +
            "  \"httpResponse\": { \"body\": \"someBody\" }" +
            "}");

        // then
        assertThat(deserialized.getNamespace(), is("team-b"));
    }

    @Test
    public void shouldOmitNamespaceWhenNotSet() {
        // given - no namespace = global (backward compatible)
        Expectation expectation = new Expectation(request().withPath("/some/path"))
            .thenRespond(response().withBody("someBody"));

        // when
        String json = serializer.serialize(expectation);
        Expectation deserialized = serializer.deserialize(json);

        // then - existing payloads are unchanged: no namespace key, null on read back
        assertThat(json.contains("\"namespace\""), is(false));
        assertThat(deserialized.getNamespace(), nullValue());
    }
}
