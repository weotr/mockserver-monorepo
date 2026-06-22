package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.BinaryRequestDefinition;
import org.mockserver.model.ConditionalRequestDefinition;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.model.ConditionalRequestDefinitionDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.ConditionalRequestDefinition.requestIf;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Round-trip serialization tests for {@link ConditionalRequestDefinition}.
 *
 * @author jamesdbloom
 */
public class ConditionalRequestDefinitionSerializationTest {

    private final ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);
    private final RequestDefinitionSerializer serializer = new RequestDefinitionSerializer(new MockServerLogger());

    @Test
    public void shouldSerializeDTOWithIfThenElse() throws Exception {
        ConditionalRequestDefinitionDTO dto = new ConditionalRequestDefinitionDTO(requestIf(
            request().withMethod("GET"),
            request().withPath("/admin"),
            request().withPath("/public")
        ));

        String json = objectWriter.writeValueAsString(dto);

        assertThat(json.contains("\"if\""), is(true));
        assertThat(json.contains("\"then\""), is(true));
        assertThat(json.contains("\"else\""), is(true));
        assertThat(json.contains("GET"), is(true));
        assertThat(json.contains("/admin"), is(true));
        assertThat(json.contains("/public"), is(true));
    }

    @Test
    public void shouldRoundTripIfThenElse() {
        ConditionalRequestDefinition original = requestIf(
            request().withMethod("GET").withHeader("X-Env", "prod"),
            request().withPath("/admin"),
            request().withPath("/public")
        );

        String json = serializer.serialize(original);
        RequestDefinition deserialized = serializer.deserialize(json);

        assertThat(deserialized, is(original));
    }

    @Test
    public void shouldRoundTripIfThenWithoutElse() {
        ConditionalRequestDefinition original = requestIf(
            request().withMethod("GET"),
            request().withPath("/admin")
        );

        String json = serializer.serialize(original);
        RequestDefinition deserialized = serializer.deserialize(json);

        assertThat(deserialized, is(original));
        assertThat(((ConditionalRequestDefinition) deserialized).getElse(), is((RequestDefinition) null));
    }

    @Test
    public void shouldRoundTripNestedConditional() {
        ConditionalRequestDefinition original = requestIf(
            request().withMethod("GET"),
            requestIf(
                request().withHeader("X-Env", "prod"),
                request().withPath("/admin")
            )
        );

        String json = serializer.serialize(original);
        RequestDefinition deserialized = serializer.deserialize(json);

        assertThat(deserialized, is(original));
        assertThat(((ConditionalRequestDefinition) deserialized).getThen() instanceof ConditionalRequestDefinition, is(true));
    }

    @Test
    public void shouldRoundTripExpectationWithConditionalRequest() {
        Expectation original = new Expectation(requestIf(
            request().withMethod("GET"),
            request().withPath("/admin"),
            request().withPath("/public")
        )).thenRespond(response().withStatusCode(200));

        ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());
        String json = expectationSerializer.serialize(original);
        Expectation deserialized = expectationSerializer.deserialize(json);

        assertThat(deserialized.getHttpRequest() instanceof ConditionalRequestDefinition, is(true));
        assertThat(deserialized.getHttpRequest(), is((RequestDefinition) original.getHttpRequest()));
    }

    @Test
    public void shouldRejectUnsupportedBranchType() {
        // binary/DNS request definitions are not supported as conditional branches, keeping the
        // DTO mapping in lock step with the conditionalRequestDefinition JSON schema
        ConditionalRequestDefinition conditional = requestIf(
            BinaryRequestDefinition.binaryRequest("hello".getBytes()),
            request().withPath("/admin")
        );

        assertThrows(IllegalArgumentException.class, () -> new ConditionalRequestDefinitionDTO(conditional));
    }

    @Test
    public void shouldDeserializeFromHandWrittenJson() {
        String json = "{" +
            "  \"if\": { \"method\": \"GET\" }," +
            "  \"then\": { \"path\": \"/admin\" }," +
            "  \"else\": { \"path\": \"/public\" }" +
            "}";

        RequestDefinition deserialized = serializer.deserialize(json);

        assertThat(deserialized instanceof ConditionalRequestDefinition, is(true));
        ConditionalRequestDefinition conditional = (ConditionalRequestDefinition) deserialized;
        assertThat(conditional.getIf(), is((RequestDefinition) request().withMethod("GET")));
        assertThat(conditional.getThen(), is((RequestDefinition) request().withPath("/admin")));
        assertThat(conditional.getElse(), is((RequestDefinition) request().withPath("/public")));
    }
}
