package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class MockServerMatcherBasicResponsesTest {

    private RequestMatchers requestMatchers;

    @Before
    public void prepareTestFixture() {
        Scheduler scheduler = mock(Scheduler.class);
        WebSocketClientRegistry webSocketClientRegistry = mock(WebSocketClientRegistry.class);
        requestMatchers = new RequestMatchers(configuration(), new MockServerLogger(), scheduler, webSocketClientRegistry);
    }

    @Test
    public void respondWhenPathMatches() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath")).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(expectation));
    }

    @Test
    public void respondWhenRegexPathMatches() {
        // when
        Expectation expectation = new Expectation(request().withPath("[a-zA-Z]*")).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath")), is(expectation));
    }

    @Test
    public void doNotRespondWhenPathDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withPath("somePath")).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("someOtherPath")), nullValue());
    }

    @Test
    public void doNotRespondWhenRegexPathDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withPath("[a-z]*")).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("someOtherPath123")), nullValue());
    }

    @Test
    public void respondWhenPathMatchesAndAdditionalHeaders() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath")).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withHeaders(new Header("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenPathMatchesAndAdditionalQueryStringParameters() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath")).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withQueryStringParameter(new Parameter("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenPathMatchesAndAdditionalBodyParameters() {
        // when
        Expectation expectation = new Expectation(request().withPath("somePath")).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withBody(new ParameterBody(new Parameter("name", "value")))), is(expectation));
    }

    @Test
    public void respondWhenBodyMatches() {
        // when
        Expectation expectation = new Expectation(request().withBody(new StringBody("someBody"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new StringBody("someBody"))), is(expectation));
    }

    @Test
    public void respondWhenRegexBodyMatches() {
        // when
        Expectation expectation = new Expectation(request().withBody(new RegexBody("[a-zA-Z]*"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new StringBody("someBody"))), is(expectation));
    }

    @Test
    public void doNotRespondWhenBodyDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withBody(new StringBody("someBody"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new StringBody("someOtherBody"))), nullValue());
    }

    @Test
    public void doNotRespondWhenRegexBodyDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withBody(new RegexBody("[a-z]*"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new StringBody("someOtherBody123"))), nullValue());
    }

    @Test
    public void respondWhenBodyMatchesAndAdditionalHeaders() {
        // when
        Expectation expectation = new Expectation(request().withBody(new StringBody("someBody"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new StringBody("someBody")).withHeaders(new Header("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenBodyMatchesAndAdditionalQueryStringParameters() {
        // when
        Expectation expectation = new Expectation(request().withBody(new StringBody("someBody"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new StringBody("someBody")).withQueryStringParameter(new Parameter("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenAdditionalBodyParameters() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter("name", "value")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(new Parameter("name", "value"), new Parameter("additionalName", "additionalValue")))), is(expectation));
    }

    @Test
    public void respondWhenHeaderMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withHeaders(new Header("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withHeaders(new Header("name", "value"))), is(expectation));
    }

    @Test
    public void doNotRespondWhenHeaderWithMultipleValuesDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withHeaders(new Header("name", "value1", "value2"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withHeaders(new Header("name", "value1", "value3"))), nullValue());
    }

    @Test
    public void doNotRespondWhenHeaderWithMultipleValuesHasMissingValue() {
        // when
        requestMatchers.add(new Expectation(request().withHeaders(new Header("name", "value1", "value2"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withHeaders(new Header("name", "value1"))), nullValue());
    }

    @Test
    public void respondWhenHeaderMatchesAndExtraHeaders() {
        // when
        Expectation expectation = new Expectation(request().withHeaders(new Header("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withHeaders(new Header("nameExtra", "valueExtra"), new Header("name", "value"), new Header("nameExtraExtra", "valueExtraExtra"))), is(expectation));
    }

    @Test
    public void respondWhenHeaderMatchesAndPathDifferent() {
        // when
        Expectation expectation = new Expectation(request().withHeaders(new Header("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withHeaders(new Header("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenQueryStringMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameter(new Parameter("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameter(new Parameter("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenBodyParametersMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter("name", "value")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(new Parameter("name", "value")))), is(expectation));
    }

    @Test
    public void respondWhenQueryStringWithMultipleValuesMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameter(new Parameter("name", "value1", "value2"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameter(new Parameter("name", "value1", "value2"))), is(expectation));
    }

    @Test
    public void respondWhenBodyParametersWithMultipleValuesMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter("name", "value1", "value2")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(new Parameter("name", "value1", "value2")))), is(expectation));
    }

    @Test
    public void doNotRespondWhenQueryStringWithMultipleValuesDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withQueryStringParameter(new Parameter("name", "value1", "value2"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameter(new Parameter("name", "value1", "value3"))), nullValue());
    }

    @Test
    public void doNotRespondWhenBodyParametersWithMultipleValuesDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withBody(new ParameterBody(new Parameter("name", "value1", "value2")))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(new Parameter("name", "value1", "value3")))), nullValue());
    }

    @Test
    public void doNotRespondWhenQueryStringWithMultipleValuesHasMissingValue() {
        // when
        requestMatchers.add(new Expectation(request().withQueryStringParameter(new Parameter("name", "value1", "value2"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameter(new Parameter("name", "value1"))), nullValue());
    }

    @Test
    public void doNotRespondWhenBodyParametersWithMultipleValuesHasMissingValue() {
        // when
        requestMatchers.add(new Expectation(request().withBody(new ParameterBody(new Parameter("name", "value1", "value2")))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(new Parameter("name", "value1")))), nullValue());
    }

    @Test
    public void respondWhenQueryStringMatchesAndExtraParameters() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameter(new Parameter(".*name", "value.*"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(
            new Parameter("nameExtra", "valueExtra"),
            new Parameter("name", "value"),
            new Parameter("nameExtraExtra", "valueExtraExtra")
        )), is(expectation));
    }

    @Test
    public void respondWhenBodyParameterMatchesAndExtraParameters() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter(".*name", "value.*")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(
            new Parameter("nameExtra", "valueExtra"),
            new Parameter("name", "value"),
            new Parameter("nameExtraExtra", "valueExtraExtra")
        ))), is(expectation));
    }

    @Test
    public void respondWhenQueryStringParametersMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameters(new Parameter("name", "val[a-z]{2}"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(new Parameter("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenQueryStringParametersWithMultipleValuesMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameters(new Parameter("name", "valueOne", "valueTwo"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(new Parameter("name", "valueOne", "valueTwo"))), is(expectation));
    }

    @Test
    public void doNotRespondWhenQueryStringParametersWithMultipleValuesDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withQueryStringParameters(new Parameter("name", "valueOne", "valueTwo"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(new Parameter("name", "valueOne", "valueThree"))), nullValue());
    }

    @Test
    public void doNotRespondWhenQueryStringParametersWithMultipleValuesHasMissingValue() {
        // when
        requestMatchers.add(new Expectation(request().withQueryStringParameters(new Parameter("name", "valueOne", "valueTwo"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(new Parameter("name", "valueOne"))), nullValue());
    }

    @Test
    public void respondWhenQueryStringParameterMatchesAndExtraQueryStringParameters() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameters(new Parameter("name", "val[a-z]{2}"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(
            new Parameter("nameExtra", "valueExtra"),
            new Parameter("name", "value"),
            new Parameter("nameExtraExtra", "valueExtraExtra")
        )), is(expectation));
    }

    @Test
    public void respondWhenQueryStringParameterMatchesAndExtraBodyParameters() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter("name", "val[a-z]{2}")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(
            new Parameter("nameExtra", "valueExtra"),
            new Parameter("name", "value"),
            new Parameter("nameExtraExtra", "valueExtraExtra")
        ))), is(expectation));
    }

    @Test
    public void respondWhenQueryStringParameterMatchesAndMultipleQueryStringParameters() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameters(new Parameter("nameOne", "valueOne"), new Parameter("nameTwo", "valueTwo"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(
            new Parameter("nameOne", "valueOne"),
            new Parameter("nameTwo", "valueTwo")
        )), is(expectation));
    }

    @Test
    public void respondWhenQueryStringParameterMatchesAndMultipleBodyParameters() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter("nameOne", "valueOne"), new Parameter("nameTwo", "valueTwo")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(
            new Parameter("nameOne", "valueOne"),
            new Parameter("nameTwo", "valueTwo")
        ))), is(expectation));
    }

    @Test
    public void doNotRespondWhenQueryStringParameterDoesNotMatch() {
        // when
        requestMatchers.add(new Expectation(request().withQueryStringParameters(new Parameter("nameOne", "valueTwo", "valueTwo"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(
            new Parameter("nameOne", "valueOne"),
            new Parameter("nameTwo", "valueTwo")
        )), nullValue());
    }

    @Test
    public void doNotRespondWhenQueryStringParameterDoesNotMatchAndMultipleQueryStringParameters() {
        // when
        requestMatchers.add(new Expectation(request().withQueryStringParameters(new Parameter("nameOne", "valueTwo"), new Parameter("nameTwo", "valueOne"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withQueryStringParameters(
            new Parameter("nameOne", "valueOne"),
            new Parameter("nameTwo", "valueTwo")
        )), nullValue());
    }

    @Test
    public void doNotRespondWhenQueryStringParameterDoesNotMatchAndMultipleBodyParameters() {
        // when
        requestMatchers.add(new Expectation(request().withBody(new ParameterBody(new Parameter("nameOne", "valueOne"), new Parameter("nameTwo", "valueTwo")))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withBody(new ParameterBody(
            new Parameter("nameOne", "valueTwo"),
            new Parameter("nameTwo", "valueOne")
        ))), nullValue());
    }

    @Test
    public void respondWhenQueryStringParameterMatchesAndPathDifferent() {
        // when
        Expectation expectation = new Expectation(request().withQueryStringParameters(new Parameter("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withQueryStringParameters(new Parameter("name", "value"))), is(expectation));
    }

    @Test
    public void respondWhenBodyParameterMatchesAndPathDifferent() {
        // when
        Expectation expectation = new Expectation(request().withBody(new ParameterBody(new Parameter("name", "value")))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withBody(new ParameterBody(new Parameter("name", "value")))), is(expectation));
    }

    @Test
    public void respondWhenCookieMatchesExactly() {
        // when
        Expectation expectation = new Expectation(request().withCookies(new Cookie("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withCookies(new Cookie("name", "value"))), is(expectation));
    }

    @Test
    public void doNotRespondWhenCookieDoesNotMatchValue() {
        // when
        requestMatchers.add(new Expectation(request().withCookies(new Cookie("name", "value1"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withCookies(new Cookie("name", "value2"))), nullValue());
    }

    @Test
    public void doNotRespondWhenCookieDoesNotMatchName() {
        // when
        requestMatchers.add(new Expectation(request().withCookies(new Cookie("name1", "value"))).thenRespond(response().withBody("someBody")), API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withCookies(new Cookie("name2", "value"))), nullValue());
    }

    @Test
    public void respondWhenCookieMatchesAndExtraCookies() {
        // when
        Expectation expectation = new Expectation(request().withCookies(new Cookie("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withCookies(new Cookie("nameExtra", "valueExtra"), new Cookie("name", "value"), new Cookie("nameExtraExtra", "valueExtraExtra"))), is(expectation));
    }

    @Test
    public void respondWhenCookieMatchesAndPathDifferent() {
        // when
        Expectation expectation = new Expectation(request().withCookies(new Cookie("name", "value"))).thenRespond(response().withBody("someBody"));
        requestMatchers.add(expectation, API);

        // then
        assertThat(requestMatchers.firstMatchingExpectation(new HttpRequest().withPath("somePath").withCookies(new Cookie("name", "value"))), is(expectation));
    }
}
