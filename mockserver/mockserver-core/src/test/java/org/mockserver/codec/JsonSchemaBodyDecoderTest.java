package org.mockserver.codec;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertSame;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Verifies that {@link JsonSchemaBodyDecoder#convertToJson} memoizes the expensive XML-to-JSON body
 * conversion on the request, so that an incoming request matched against many candidate expectations
 * during a match scan parses its body once rather than once per expectation — without changing the
 * converted value, the fall-back behaviour, or the (non-)propagation of exceptions.
 */
public class JsonSchemaBodyDecoderTest {

    private static final String XML_BODY = "<notes><note><title>hello</title></note></notes>";

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(JsonSchemaBodyDecoderTest.class);

    private JsonSchemaBodyDecoder decoder(HttpRequest httpRequest) {
        // each expectation owns its own decoder instance (one per HttpRequestPropertiesMatcher),
        // mirroring the production wiring; the memoization must therefore live on the request
        return new JsonSchemaBodyDecoder(configuration, mockServerLogger, new Expectation(httpRequest), httpRequest);
    }

    @Test
    public void xmlBodyConvertedOncePerRequestAcrossMultipleExpectationDecoders() {
        // given - one incoming request with an XML body, as it is during a match scan
        HttpRequest request = request()
            .withHeader("Content-Type", "application/xml")
            .withBody(XML_BODY);

        // when - the body is converted by several different decoders (one per candidate expectation)
        String first = decoder(request).convertToJson(request, null);
        String second = decoder(request).convertToJson(request, null);
        String third = decoder(request).convertToJson(request, null);

        // then - the conversion produced JSON derived from the XML
        assertThat(first, containsString("note"));
        assertThat(first, containsString("hello"));

        // and - the body was parsed exactly once: repeat calls return the very same cached String
        // instance (a fresh DOM parse + ObjectMapper serialisation would create a new String)
        assertSame("XML body should be parsed once and the result cached on the request", first, second);
        assertSame("XML body should be parsed once and the result cached on the request", first, third);
    }

    @Test
    public void getOrComputeConvertedBodySupplierRunsExactlyOnce() {
        // given
        HttpRequest request = request()
            .withHeader("Content-Type", "application/xml")
            .withBody(XML_BODY);
        AtomicInteger parseCount = new AtomicInteger(0);

        // when - the same conversion target is requested repeatedly
        for (int i = 0; i < 5; i++) {
            request.getOrComputeConvertedBody(HttpRequest.ConvertedBodyType.XML_TO_JSON, () -> {
                parseCount.incrementAndGet();
                return "computed";
            });
        }

        // then - the expensive supplier ran only once
        assertThat("supplier should be invoked once and the result memoized", parseCount.get(), is(1));
    }

    @Test
    public void changingBodyInvalidatesTheConvertedBodyCache() {
        // given - a request whose XML body has already been converted and cached
        HttpRequest request = request()
            .withHeader("Content-Type", "application/xml")
            .withBody(XML_BODY);
        String firstConversion = decoder(request).convertToJson(request, null);

        // when - the body is replaced
        String replacementBody = "<notes><note><title>goodbye</title></note></notes>";
        request.withBody(replacementBody);
        String secondConversion = decoder(request).convertToJson(request, null);

        // then - the stale cached value is not returned; the new body is parsed
        assertThat(secondConversion, containsString("goodbye"));
        assertThat(firstConversion, containsString("hello"));
    }

    @Test
    public void malformedXmlBodyFallsBackToOriginalBodyIdenticallyOnRepeat() {
        // given - a request with a fatally malformed XML body
        String malformedXml = "<notes><note><title>hello</title></note>";
        HttpRequest request = request()
            .withHeader("Content-Type", "application/xml")
            .withBody(malformedXml);

        // when - converted repeatedly (as across multiple expectations)
        String first = decoder(request).convertToJson(request, null);
        String second = decoder(request).convertToJson(request, null);

        // then - no exception is propagated and it falls back to the original body string, identically
        assertThat("malformed XML falls back to the original body string", first, is(malformedXml));
        assertThat("malformed XML falls back to the original body string", second, is(malformedXml));
        // and - the fall-back is cached too (same instance), so it is not re-attempted per expectation
        assertThat(second, sameInstance(first));
    }

    @Test
    public void jsonBodyIsReturnedUnchanged() {
        // given - a JSON body (the cheap path, returned verbatim, not parsed)
        String jsonBody = "{\"name\":\"value\"}";
        HttpRequest request = request()
            .withHeader("Content-Type", "application/json")
            .withBody(jsonBody);

        // when
        String converted = decoder(request).convertToJson(request, null);

        // then - the JSON body is returned unchanged
        assertThat(converted, is(jsonBody));
    }
}
