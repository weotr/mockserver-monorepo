package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

/**
 * Tests for {@link AsyncApiHttpExpectationGenerator}, the AsyncAPI-to-HTTP-expectation import.
 */
public class AsyncApiHttpExpectationGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsyncApiHttpExpectationGenerator generator = new AsyncApiHttpExpectationGenerator();

    private static final String SPEC = "{\n" +
        "  \"asyncapi\": \"2.6.0\",\n" +
        "  \"info\": { \"title\": \"User Events\", \"version\": \"1.0.0\" },\n" +
        "  \"channels\": {\n" +
        "    \"user.signedup\": {\n" +
        "      \"publish\": {\n" +
        "        \"message\": {\n" +
        "          \"payload\": {\n" +
        "            \"type\": \"object\",\n" +
        "            \"properties\": {\n" +
        "              \"userId\": { \"type\": \"string\" },\n" +
        "              \"email\": { \"type\": \"string\" }\n" +
        "            },\n" +
        "            \"example\": { \"userId\": \"abc123\", \"email\": \"user@test.com\" }\n" +
        "          }\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    \"order/created\": {\n" +
        "      \"publish\": {\n" +
        "        \"message\": {\n" +
        "          \"payload\": {\n" +
        "            \"type\": \"object\",\n" +
        "            \"properties\": { \"orderId\": { \"type\": \"string\" } }\n" +
        "          }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    @Test
    public void shouldGenerateOneGetExpectationPerChannel() throws Exception {
        List<Expectation> expectations = generator.generate(SPEC);

        assertThat(expectations, hasSize(2));

        Expectation signedUp = byPath(expectations, "/user/signedup");
        HttpRequest request = (HttpRequest) signedUp.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));

        HttpResponse response = signedUp.getHttpResponse();
        assertThat(response.getStatusCode(), is(200));
        // the spec's explicit example payload is served verbatim
        JsonNode body = MAPPER.readTree(response.getBodyAsString());
        assertThat(body.get("userId").asText(), is("abc123"));
        assertThat(body.get("email").asText(), is("user@test.com"));

        // a channel with no example synthesizes a schema-aware example
        Expectation orderCreated = byPath(expectations, "/order/created");
        JsonNode orderBody = MAPPER.readTree(orderCreated.getHttpResponse().getBodyAsString());
        assertThat(orderBody.has("orderId"), is(true));
    }

    @Test
    public void shouldApplyChannelPathPrefixFromWrapper() {
        String wrapped = "{\"spec\": " + SPEC + ", \"channelPathPrefix\": \"/events\"}";

        List<Expectation> expectations = generator.generate(wrapped);

        assertThat(expectations, hasSize(2));
        assertThat(((HttpRequest) byPath(expectations, "/events/user/signedup").getHttpRequest())
            .getPath().getValue(), is("/events/user/signedup"));
    }

    @Test
    public void shouldSerializeToExpectationJsonArray() throws Exception {
        String json = generator.generateSerialized(SPEC);
        JsonNode array = MAPPER.readTree(json);
        assertThat(array.isArray(), is(true));
        assertThat(array.size(), is(2));
        assertThat(array.get(0).get("httpRequest").get("method").asText(), is("GET"));
    }

    @Test
    public void shouldRejectBlankBody() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generator.generate("  "));
        assertThat(e.getMessage(), containsString("AsyncAPI spec"));
    }

    @Test
    public void shouldRejectMalformedSpec() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> generator.generate("this is not a spec at all: ::"));
        assertThat(e.getMessage(), anyOf(containsString("unable to parse AsyncAPI spec"), containsString("no channels")));
    }

    @Test
    public void shouldRejectSpecWithNoChannels() {
        String noChannels = "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"Empty\",\"version\":\"1.0.0\"},\"channels\":{}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generator.generate(noChannels));
        assertThat(e.getMessage(), containsString("no channels"));
    }

    @Test
    public void shouldDisambiguateCollidingChannelPaths() {
        // "a.b" and "a/b" both normalise to /a/b — the second must be made unique
        String spec = "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"C\",\"version\":\"1.0.0\"}," +
            "\"channels\":{\"a.b\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"object\"}}}}," +
            "\"a/b\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"object\"}}}}}}";

        List<Expectation> expectations = generator.generate(spec);

        assertThat(expectations, hasSize(2));
        List<String> paths = expectations.stream()
            .map(e -> ((HttpRequest) e.getHttpRequest()).getPath().getValue())
            .collect(java.util.stream.Collectors.toList());
        assertThat(paths, hasItem("/a/b"));
        assertThat(paths, hasItem("/a/b/2"));
    }

    private static Expectation byPath(List<Expectation> expectations, String path) {
        return expectations.stream()
            .filter(e -> path.equals(((HttpRequest) e.getHttpRequest()).getPath().getValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no expectation for path " + path + " in " + expectations));
    }
}
