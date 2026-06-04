package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.ResponseMode;
import org.mockserver.model.*;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class ExpectationDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // given
        HttpRequest httpRequest = new HttpRequest().withBody("some_body");
        HttpResponse httpResponse = new HttpResponse().withBody("some_response_body");
        HttpTemplate httpResponseTemplate = new HttpTemplate(HttpTemplate.TemplateType.JAVASCRIPT).withTemplate("some_repoonse_template");
        HttpClassCallback httpResponseClassCallback = new HttpClassCallback().withCallbackClass("some_response_class");
        HttpObjectCallback httpResponseObjectCallback = new HttpObjectCallback().withClientId("some_response_client_id");
        HttpForward httpForward = new HttpForward().withHost("some_host");
        HttpTemplate httpForwardTemplate = new HttpTemplate(HttpTemplate.TemplateType.VELOCITY).withTemplate("some_forward_template");
        HttpClassCallback httpForwardClassCallback = new HttpClassCallback().withCallbackClass("some_forward_class");
        HttpObjectCallback httpForwardObjectCallback = new HttpObjectCallback().withClientId("some_forward_client_id");
        HttpOverrideForwardedRequest httpOverrideForwardedRequest = new HttpOverrideForwardedRequest().withRequestOverride(httpRequest);
        HttpError httpError = new HttpError().withResponseBytes("some_bytes".getBytes(UTF_8));
        Times times = Times.exactly(3);
        TimeToLive timeToLive = TimeToLive.unlimited();
        int priority = 0;

        // when
        ExpectationDTO expectationWithResponse = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponse));

        // then
        assertThat(expectationWithResponse.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithResponse.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithResponse.getPriority(), is(priority));
        assertThat(expectationWithResponse.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithResponse.getHttpResponse(), is(new HttpResponseDTO(httpResponse)));
        assertThat(expectationWithResponse.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithResponse.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpForward(), nullValue());
        assertThat(expectationWithResponse.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponse.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponse.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithResponseTemplate = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponseTemplate));

        // then
        assertThat(expectationWithResponseTemplate.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithResponseTemplate.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithResponseTemplate.getPriority(), is(priority));
        assertThat(expectationWithResponseTemplate.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithResponseTemplate.getHttpResponse(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpResponseTemplate(), is(new HttpTemplateDTO(httpResponseTemplate)));
        assertThat(expectationWithResponseTemplate.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForward(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithResponseClassCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponseClassCallback));

        // then
        assertThat(expectationWithResponseClassCallback.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithResponseClassCallback.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithResponseClassCallback.getPriority(), is(priority));
        assertThat(expectationWithResponseClassCallback.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithResponseClassCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpResponseClassCallback(), is(new HttpClassCallbackDTO(httpResponseClassCallback)));
        assertThat(expectationWithResponseClassCallback.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForward(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithResponseObjectCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponseObjectCallback));

        // then
        assertThat(expectationWithResponseObjectCallback.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithResponseObjectCallback.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithResponseObjectCallback.getPriority(), is(priority));
        assertThat(expectationWithResponseObjectCallback.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithResponseObjectCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpResponseObjectCallback(), is(new HttpObjectCallbackDTO(httpResponseObjectCallback)));
        assertThat(expectationWithResponseObjectCallback.getHttpForward(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithForward = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForward));

        // then
        assertThat(expectationWithForward.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithForward.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithForward.getPriority(), is(priority));
        assertThat(expectationWithForward.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithForward.getHttpResponse(), nullValue());
        assertThat(expectationWithForward.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForward.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForward.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForward.getHttpForward(), is(new HttpForwardDTO(httpForward)));
        assertThat(expectationWithForward.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithForward.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithForward.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithForward.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForward.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithForwardTemplate = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForwardTemplate));

        // then
        assertThat(expectationWithForwardTemplate.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithForwardTemplate.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithForwardTemplate.getPriority(), is(priority));
        assertThat(expectationWithForwardTemplate.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithForwardTemplate.getHttpResponse(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpForward(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpForwardTemplate(), is(new HttpTemplateDTO(httpForwardTemplate)));
        assertThat(expectationWithForwardTemplate.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithForwardClassCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForwardClassCallback));

        // then
        assertThat(expectationWithForwardClassCallback.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithForwardClassCallback.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithForwardClassCallback.getPriority(), is(priority));
        assertThat(expectationWithForwardClassCallback.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithForwardClassCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpForward(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpForwardClassCallback(), is(new HttpClassCallbackDTO(httpForwardClassCallback)));
        assertThat(expectationWithForwardClassCallback.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithForwardObjectCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForwardObjectCallback));

        // then
        assertThat(expectationWithForwardObjectCallback.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithForwardObjectCallback.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithForwardObjectCallback.getPriority(), is(priority));
        assertThat(expectationWithForwardObjectCallback.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithForwardObjectCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForward(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForwardObjectCallback(), is(new HttpObjectCallbackDTO(httpForwardObjectCallback)));
        assertThat(expectationWithForwardObjectCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithOverrideForwardedRequest = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpOverrideForwardedRequest));

        // then
        assertThat(expectationWithOverrideForwardedRequest.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithOverrideForwardedRequest.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithOverrideForwardedRequest.getPriority(), is(priority));
        assertThat(expectationWithOverrideForwardedRequest.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponse(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForward(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpOverrideForwardedRequest(), is(new HttpOverrideForwardedRequestDTO(httpOverrideForwardedRequest)));
        assertThat(expectationWithOverrideForwardedRequest.getHttpError(), nullValue());

        // when
        ExpectationDTO expectationWithError = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenError(httpError));

        // then
        assertThat(expectationWithError.getTimes(), is(new TimesDTO(times)));
        assertThat(expectationWithError.getTimeToLive(), is(new TimeToLiveDTO(timeToLive)));
        assertThat(expectationWithError.getPriority(), is(priority));
        assertThat(expectationWithError.getHttpRequest(), is(new HttpRequestDTO(httpRequest)));
        assertThat(expectationWithError.getHttpResponse(), nullValue());
        assertThat(expectationWithError.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithError.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithError.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithError.getHttpForward(), nullValue());
        assertThat(expectationWithError.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithError.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithError.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithError.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithError.getHttpError(), is(new HttpErrorDTO(httpError)));
    }

    @Test
    public void shouldBuildObject() {
        // given
        HttpRequest httpRequest = new HttpRequest().withBody("some_body");
        HttpResponse httpResponse = new HttpResponse().withBody("some_response_body");
        HttpTemplate httpResponseTemplate = new HttpTemplate(HttpTemplate.TemplateType.JAVASCRIPT).withTemplate("some_repoonse_template");
        HttpForward httpForward = new HttpForward().withHost("some_host");
        HttpTemplate httpForwardTemplate = new HttpTemplate(HttpTemplate.TemplateType.VELOCITY).withTemplate("some_forward_template");
        HttpError httpError = new HttpError().withResponseBytes("some_bytes".getBytes(UTF_8));
        HttpClassCallback httpClassCallback = new HttpClassCallback().withCallbackClass("some_class");
        HttpObjectCallback httpObjectCallback = new HttpObjectCallback().withClientId("some_client_id");
        HttpOverrideForwardedRequest httpOverrideForwardedRequest = new HttpOverrideForwardedRequest().withRequestOverride(httpRequest);
        Times times = Times.exactly(3);
        TimeToLive timeToLive = TimeToLive.unlimited();
        int priority = 0;

        // when
        Expectation expectationWithResponse = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponse)).buildObject();

        // then
        assertThat(expectationWithResponse.getTimes(), is(times));
        assertThat(expectationWithResponse.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithResponse.getPriority(), is(priority));
        assertThat(expectationWithResponse.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithResponse.getHttpResponse(), is(httpResponse));
        assertThat(expectationWithResponse.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithResponse.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpForward(), nullValue());
        assertThat(expectationWithResponse.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponse.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponse.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponse.getHttpError(), nullValue());

        // when
        Expectation expectationWithResponseTemplate = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpResponseTemplate)).buildObject();

        // then
        assertThat(expectationWithResponseTemplate.getTimes(), is(times));
        assertThat(expectationWithResponseTemplate.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithResponseTemplate.getPriority(), is(priority));
        assertThat(expectationWithResponseTemplate.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithResponseTemplate.getHttpResponse(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpResponseTemplate(), is(httpResponseTemplate));
        assertThat(expectationWithResponseTemplate.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForward(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponseTemplate.getHttpError(), nullValue());

        // when
        Expectation expectationWithResponseClassCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpClassCallback)).buildObject();

        // then
        assertThat(expectationWithResponseClassCallback.getTimes(), is(times));
        assertThat(expectationWithResponseClassCallback.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithResponseClassCallback.getPriority(), is(priority));
        assertThat(expectationWithResponseClassCallback.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithResponseClassCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpResponseClassCallback(), is(httpClassCallback));
        assertThat(expectationWithResponseClassCallback.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForward(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponseClassCallback.getHttpError(), nullValue());

        // when
        Expectation expectationWithResponseObjectCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenRespond(httpObjectCallback)).buildObject();

        // then
        assertThat(expectationWithResponseObjectCallback.getTimes(), is(times));
        assertThat(expectationWithResponseObjectCallback.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithResponseObjectCallback.getPriority(), is(priority));
        assertThat(expectationWithResponseObjectCallback.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithResponseObjectCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpResponseObjectCallback(), is(httpObjectCallback));
        assertThat(expectationWithResponseObjectCallback.getHttpForward(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithResponseObjectCallback.getHttpError(), nullValue());

        // when
        Expectation expectationWithForward = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForward)).buildObject();

        // then
        assertThat(expectationWithForward.getTimes(), is(times));
        assertThat(expectationWithForward.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithForward.getPriority(), is(priority));
        assertThat(expectationWithForward.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithForward.getHttpResponse(), nullValue());
        assertThat(expectationWithForward.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForward.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForward.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForward.getHttpForward(), is(httpForward));
        assertThat(expectationWithForward.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithForward.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithForward.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithForward.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForward.getHttpError(), nullValue());

        // when
        Expectation expectationWithForwardTemplate = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpForwardTemplate)).buildObject();

        // then
        assertThat(expectationWithForwardTemplate.getTimes(), is(times));
        assertThat(expectationWithForwardTemplate.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithForwardTemplate.getPriority(), is(priority));
        assertThat(expectationWithForwardTemplate.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithForwardTemplate.getHttpResponse(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpForward(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpForwardTemplate(), is(httpForwardTemplate));
        assertThat(expectationWithForwardTemplate.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForwardTemplate.getHttpError(), nullValue());

        // when
        Expectation expectationWithForwardClassCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpClassCallback)).buildObject();

        // then
        assertThat(expectationWithForwardClassCallback.getTimes(), is(times));
        assertThat(expectationWithForwardClassCallback.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithForwardClassCallback.getPriority(), is(priority));
        assertThat(expectationWithForwardClassCallback.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithForwardClassCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpForward(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpForwardClassCallback(), is(httpClassCallback));
        assertThat(expectationWithForwardClassCallback.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForwardClassCallback.getHttpError(), nullValue());

        // when
        Expectation expectationWithForwardObjectCallback = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpObjectCallback)).buildObject();

        // then
        assertThat(expectationWithForwardObjectCallback.getTimes(), is(times));
        assertThat(expectationWithForwardObjectCallback.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithForwardObjectCallback.getPriority(), is(priority));
        assertThat(expectationWithForwardObjectCallback.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithForwardObjectCallback.getHttpResponse(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForward(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpForwardObjectCallback(), is(httpObjectCallback));
        assertThat(expectationWithForwardObjectCallback.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithForwardObjectCallback.getHttpError(), nullValue());

        // when
        Expectation expectationWithOverrideForwardedRequest = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenForward(httpOverrideForwardedRequest)).buildObject();

        // then
        assertThat(expectationWithOverrideForwardedRequest.getTimes(), is(times));
        assertThat(expectationWithOverrideForwardedRequest.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithOverrideForwardedRequest.getPriority(), is(priority));
        assertThat(expectationWithOverrideForwardedRequest.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponse(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForward(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithOverrideForwardedRequest.getHttpOverrideForwardedRequest(), is(httpOverrideForwardedRequest));
        assertThat(expectationWithOverrideForwardedRequest.getHttpError(), nullValue());

        // when
        Expectation expectationWithError = new ExpectationDTO(new Expectation(httpRequest, times, timeToLive, priority).thenError(httpError)).buildObject();

        // then
        assertThat(expectationWithError.getTimes(), is(times));
        assertThat(expectationWithError.getTimeToLive(), is(timeToLive));
        assertThat(expectationWithError.getPriority(), is(priority));
        assertThat(expectationWithError.getHttpRequest(), is(httpRequest));
        assertThat(expectationWithError.getHttpResponse(), nullValue());
        assertThat(expectationWithError.getHttpResponseTemplate(), nullValue());
        assertThat(expectationWithError.getHttpResponseClassCallback(), nullValue());
        assertThat(expectationWithError.getHttpResponseObjectCallback(), nullValue());
        assertThat(expectationWithError.getHttpForward(), nullValue());
        assertThat(expectationWithError.getHttpForwardTemplate(), nullValue());
        assertThat(expectationWithError.getHttpForwardClassCallback(), nullValue());
        assertThat(expectationWithError.getHttpForwardObjectCallback(), nullValue());
        assertThat(expectationWithError.getHttpOverrideForwardedRequest(), nullValue());
        assertThat(expectationWithError.getHttpError(), is(httpError));
    }

    @Test
    public void shouldBuildObjectWithNulls() {
        // when
        Expectation expectation = new ExpectationDTO(new Expectation(null, null, null, 0).thenRespond((HttpResponse) null).thenForward((HttpForward) null).thenError(null).thenRespond((HttpClassCallback) null).thenRespond((HttpObjectCallback) null)).buildObject();

        // then
        assertThat(expectation.getTimes(), is(Times.unlimited()));
        assertThat(expectation.getTimeToLive(), is(TimeToLive.unlimited()));
        assertThat(expectation.getPriority(), is(0));
        assertThat(expectation.getHttpRequest(), is(nullValue()));
        assertThat(expectation.getHttpResponse(), is(nullValue()));
        assertThat(expectation.getHttpResponseTemplate(), is(nullValue()));
        assertThat(expectation.getHttpResponseClassCallback(), is(nullValue()));
        assertThat(expectation.getHttpResponseObjectCallback(), is(nullValue()));
        assertThat(expectation.getHttpForward(), is(nullValue()));
        assertThat(expectation.getHttpForwardTemplate(), is(nullValue()));
        assertThat(expectation.getHttpForwardClassCallback(), is(nullValue()));
        assertThat(expectation.getHttpForwardObjectCallback(), is(nullValue()));
        assertThat(expectation.getHttpOverrideForwardedRequest(), is(nullValue()));
        assertThat(expectation.getHttpError(), is(nullValue()));
    }

    @Test
    public void shouldReturnValuesSetInSetter() {
        // given
        HttpRequestDTO httpRequest = new HttpRequestDTO(new HttpRequest().withBody("some_body"));
        HttpResponseDTO httpResponse = new HttpResponseDTO(new HttpResponse().withBody("some_response_body"));
        HttpTemplateDTO httpResponseTemplate = new HttpTemplateDTO(new HttpTemplate(HttpTemplate.TemplateType.JAVASCRIPT).withTemplate("some_repoonse_template"));
        HttpClassCallbackDTO httpResponseClassCallback = new HttpClassCallbackDTO(new HttpClassCallback().withCallbackClass("some_response_class"));
        HttpObjectCallbackDTO httpResponseObjectCallback = new HttpObjectCallbackDTO(new HttpObjectCallback().withClientId("some_response_client_id"));
        HttpForwardDTO httpForward = new HttpForwardDTO(new HttpForward().withHost("some_host"));
        HttpTemplateDTO httpForwardTemplate = new HttpTemplateDTO(new HttpTemplate(HttpTemplate.TemplateType.VELOCITY).withTemplate("some_forward_template"));
        HttpClassCallbackDTO httpForwardClassCallback = new HttpClassCallbackDTO(new HttpClassCallback().withCallbackClass("some_forward_class"));
        HttpObjectCallbackDTO httpForwardObjectCallback = new HttpObjectCallbackDTO(new HttpObjectCallback().withClientId("some_forward_client_id"));
        HttpOverrideForwardedRequestDTO httpOverrideForwardedRequest = new HttpOverrideForwardedRequestDTO(new HttpOverrideForwardedRequest().withRequestOverride(request("some_path")));
        HttpErrorDTO httpError = new HttpErrorDTO(new HttpError().withResponseBytes("some_bytes".getBytes(UTF_8)));
        TimesDTO times = new TimesDTO(Times.exactly(3));
        TimeToLiveDTO timeToLive = new TimeToLiveDTO(TimeToLive.unlimited());
        int priority = 0;

        // when
        ExpectationDTO expectation = new ExpectationDTO();
        expectation.setTimes(times);
        expectation.setTimeToLive(timeToLive);
        expectation.setPriority(priority);
        expectation.setHttpRequest(httpRequest);
        expectation.setHttpResponse(httpResponse);
        expectation.setHttpResponseTemplate(httpResponseTemplate);
        expectation.setHttpResponseClassCallback(httpResponseClassCallback);
        expectation.setHttpResponseObjectCallback(httpResponseObjectCallback);
        expectation.setHttpForward(httpForward);
        expectation.setHttpForwardTemplate(httpForwardTemplate);
        expectation.setHttpForwardClassCallback(httpForwardClassCallback);
        expectation.setHttpForwardObjectCallback(httpForwardObjectCallback);
        expectation.setHttpOverrideForwardedRequest(httpOverrideForwardedRequest);
        expectation.setHttpError(httpError);

        // then
        assertThat(expectation.getTimes(), is(times));
        assertThat(expectation.getTimeToLive(), is(timeToLive));
        assertThat(expectation.getPriority(), is(priority));
        assertThat(expectation.getHttpRequest(), is(httpRequest));
        assertThat(expectation.getHttpResponse(), is(httpResponse));
        assertThat(expectation.getHttpResponseTemplate(), is(httpResponseTemplate));
        assertThat(expectation.getHttpResponseClassCallback(), is(httpResponseClassCallback));
        assertThat(expectation.getHttpResponseObjectCallback(), is(httpResponseObjectCallback));
        assertThat(expectation.getHttpForward(), is(httpForward));
        assertThat(expectation.getHttpForwardTemplate(), is(httpForwardTemplate));
        assertThat(expectation.getHttpForwardClassCallback(), is(httpForwardClassCallback));
        assertThat(expectation.getHttpForwardObjectCallback(), is(httpForwardObjectCallback));
        assertThat(expectation.getHttpOverrideForwardedRequest(), is(httpOverrideForwardedRequest));
        assertThat(expectation.getHttpError(), is(httpError));
    }

    @Test
    public void shouldHandleNullObjectInput() {
        // when
        ExpectationDTO expectationDTO = new ExpectationDTO(null);

        // then
        assertThat(expectationDTO.getTimes(), is(nullValue()));
        assertThat(expectationDTO.getTimeToLive(), is(nullValue()));
        assertThat(expectationDTO.getPriority(), is(nullValue()));
        assertThat(expectationDTO.getHttpRequest(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponse(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponseTemplate(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponseClassCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponseObjectCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpForward(), is(nullValue()));
        assertThat(expectationDTO.getHttpForwardTemplate(), is(nullValue()));
        assertThat(expectationDTO.getHttpForwardClassCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpForwardObjectCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpOverrideForwardedRequest(), is(nullValue()));
        assertThat(expectationDTO.getHttpError(), is(nullValue()));
    }

    @Test
    public void shouldHandleNullFieldInput() {
        // when
        ExpectationDTO expectationDTO = new ExpectationDTO(new Expectation(null, null, null, 0));

        // then
        assertThat(expectationDTO.getTimes(), is(nullValue()));
        assertThat(expectationDTO.getTimeToLive(), is(nullValue()));
        assertThat(expectationDTO.getPriority(), is(0));
        assertThat(expectationDTO.getHttpRequest(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponse(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponseTemplate(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponseClassCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpResponseObjectCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpForward(), is(nullValue()));
        assertThat(expectationDTO.getHttpForwardTemplate(), is(nullValue()));
        assertThat(expectationDTO.getHttpForwardClassCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpForwardObjectCallback(), is(nullValue()));
        assertThat(expectationDTO.getHttpOverrideForwardedRequest(), is(nullValue()));
        assertThat(expectationDTO.getHttpError(), is(nullValue()));
    }

    @Test
    public void shouldPreservePercentageInDTO() {
        Expectation expectation = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .withPercentage(75)
            .thenRespond(new HttpResponse().withBody("response"));

        ExpectationDTO dto = new ExpectationDTO(expectation);

        assertThat(dto.getPercentage(), is(75));
    }

    @Test
    public void shouldRoundTripPercentageThroughDTO() {
        Expectation original = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .withPercentage(42)
            .thenRespond(new HttpResponse().withBody("response"));

        ExpectationDTO dto = new ExpectationDTO(original);
        Expectation rebuilt = dto.buildObject();

        assertThat(rebuilt.getPercentage(), is(42));
    }

    @Test
    public void shouldHandleNullPercentageInDTO() {
        Expectation expectation = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(new HttpResponse().withBody("response"));

        ExpectationDTO dto = new ExpectationDTO(expectation);

        assertThat(dto.getPercentage(), is(nullValue()));

        Expectation rebuilt = dto.buildObject();
        assertThat(rebuilt.getPercentage(), is(nullValue()));
    }

    @Test
    public void shouldRoundTripHttpResponsesAndResponseMode() {
        HttpResponse r1 = new HttpResponse().withBody("one");
        HttpResponse r2 = new HttpResponse().withBody("two");
        Expectation original = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(Arrays.asList(r1, r2))
            .withResponseMode(ResponseMode.RANDOM);

        ExpectationDTO dto = new ExpectationDTO(original);
        assertThat(dto.getHttpResponses().size(), is(2));
        assertThat(dto.getResponseMode(), is(ResponseMode.RANDOM));

        Expectation rebuilt = dto.buildObject();
        assertThat(rebuilt.getHttpResponses().size(), is(2));
        assertThat(rebuilt.getResponseMode(), is(ResponseMode.RANDOM));
    }

    @Test
    public void shouldRoundTripNullHttpResponses() {
        Expectation original = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(new HttpResponse().withBody("single"));

        ExpectationDTO dto = new ExpectationDTO(original);
        assertThat(dto.getHttpResponses(), is(nullValue()));
        assertThat(dto.getResponseMode(), is(nullValue()));

        Expectation rebuilt = dto.buildObject();
        assertThat(rebuilt.getHttpResponses(), is(nullValue()));
        assertThat(rebuilt.getResponseMode(), is(nullValue()));
    }

    @Test
    public void shouldRoundTripScenarioFields() {
        Expectation original = new Expectation(request(), Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(new HttpResponse().withBody("response"))
            .withScenarioName("TestScenario")
            .withScenarioState("Started")
            .withNewScenarioState("Step2");

        ExpectationDTO dto = new ExpectationDTO(original);
        assertThat(dto.getScenarioName(), is("TestScenario"));
        assertThat(dto.getScenarioState(), is("Started"));
        assertThat(dto.getNewScenarioState(), is("Step2"));

        Expectation rebuilt = dto.buildObject();
        assertThat(rebuilt.getScenarioName(), is("TestScenario"));
        assertThat(rebuilt.getScenarioState(), is("Started"));
        assertThat(rebuilt.getNewScenarioState(), is("Step2"));
    }
}
