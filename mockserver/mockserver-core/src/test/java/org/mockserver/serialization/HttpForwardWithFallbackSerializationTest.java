package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpForwardWithFallback;
import org.mockserver.serialization.model.ExpectationDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpForwardWithFallbackSerializationTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private final ObjectWriter objectWriter = objectMapper.writerWithDefaultPrettyPrinter();

    @Test
    public void shouldSerializeAndDeserializeViaDTO() throws Exception {
        // given
        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(forward().withHost("api.example.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS))
            .withFallback(response().withStatusCode(200).withBody("cached response"))
            .withFallbackOnStatusCodes(500, 502, 503)
            .withFallbackOnTimeout(true);

        Expectation expectation = Expectation.when(request().withPath("/test"))
            .thenForwardWithFallback(action);

        // when - serialize
        ExpectationDTO dto = new ExpectationDTO(expectation);
        String json = objectWriter.writeValueAsString(dto);

        // then - JSON should contain the forward-with-fallback fields
        assertThat(json, containsString("httpForwardWithFallback"));
        assertThat(json, containsString("api.example.com"));
        assertThat(json, containsString("cached response"));
        assertThat(json, containsString("fallbackOnTimeout"));

        // when - deserialize
        ExpectationDTO deserialized = objectMapper.readValue(json, ExpectationDTO.class);
        Expectation rebuilt = deserialized.buildObject();

        // then
        assertThat(rebuilt.getHttpForwardWithFallback(), is(notNullValue()));
        assertThat(rebuilt.getHttpForwardWithFallback().getHttpForward().getHost(), is("api.example.com"));
        assertThat(rebuilt.getHttpForwardWithFallback().getHttpForward().getPort(), is(443));
        assertThat(rebuilt.getHttpForwardWithFallback().getHttpForward().getScheme(), is(HttpForward.Scheme.HTTPS));
        assertThat(rebuilt.getHttpForwardWithFallback().getFallbackResponse().getStatusCode(), is(200));
        assertThat(rebuilt.getHttpForwardWithFallback().getFallbackOnStatusCodes(), contains(500, 502, 503));
        assertThat(rebuilt.getHttpForwardWithFallback().getFallbackOnTimeout(), is(true));
    }

    @Test
    public void shouldSerializeMinimalAction() throws Exception {
        // given
        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(forward().withHost("upstream.local").withPort(8080))
            .withFallback(response().withStatusCode(200));

        Expectation expectation = Expectation.when(request().withPath("/api"))
            .thenForwardWithFallback(action);

        // when
        ExpectationDTO dto = new ExpectationDTO(expectation);
        String json = objectWriter.writeValueAsString(dto);

        ExpectationDTO deserialized = objectMapper.readValue(json, ExpectationDTO.class);
        Expectation rebuilt = deserialized.buildObject();

        // then
        assertThat(rebuilt.getHttpForwardWithFallback(), is(notNullValue()));
        assertThat(rebuilt.getHttpForwardWithFallback().getHttpForward().getHost(), is("upstream.local"));
        assertThat(rebuilt.getHttpForwardWithFallback().getFallbackResponse().getStatusCode(), is(200));
        assertThat(rebuilt.getHttpForwardWithFallback().getFallbackOnStatusCodes(), is(nullValue()));
        assertThat(rebuilt.getHttpForwardWithFallback().getFallbackOnTimeout(), is(nullValue()));
    }

    @Test
    public void shouldSerializeWithExpectationSerializer() throws Exception {
        // given
        MockServerLogger mockServerLogger = new MockServerLogger();
        ExpectationSerializer serializer = new ExpectationSerializer(mockServerLogger);

        HttpForwardWithFallback action = forwardWithFallback()
            .withForward(forward().withHost("api.example.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS))
            .withFallback(response().withStatusCode(200).withBody("cached"))
            .withFallbackOnStatusCodes(500, 503);

        Expectation expectation = Expectation.when(request().withPath("/test"))
            .thenForwardWithFallback(action);

        // when - serialize to JSON then deserialize (with schema validation)
        String json = serializer.serialize(expectation);
        Expectation deserialized = serializer.deserialize(json);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.getHttpForwardWithFallback(), is(notNullValue()));
        assertThat(deserialized.getHttpForwardWithFallback().getHttpForward().getHost(), is("api.example.com"));
        assertThat(deserialized.getHttpForwardWithFallback().getFallbackResponse().getStatusCode(), is(200));
        assertThat(deserialized.getHttpForwardWithFallback().getFallbackOnStatusCodes(), contains(500, 503));
    }
}
