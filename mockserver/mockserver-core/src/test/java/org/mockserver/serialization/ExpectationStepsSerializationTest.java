package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.Delay.seconds;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Round-trip serialization tests for the unified ordered {@code steps} model.
 * Verifies DTO mapping, JSON schema acceptance, and backward compatibility
 * (existing beforeActions expectations are unaffected).
 */
public class ExpectationStepsSerializationTest {

    private final ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

    @Test
    public void shouldRoundTripSimpleSingleResponderStep() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpResponse(response("ok"))
                    .withResponder(true)
            );

        // when
        String json = serializer.serialize(expectation);
        Expectation parsed = serializer.deserialize(json);

        // then
        List<ExpectationStep> steps = parsed.getSteps();
        assertThat(steps, hasSize(1));
        ExpectationStep step = steps.get(0);
        assertThat(step.getHttpResponse().getBodyAsString(), is("ok"));
        assertThat(step.getResponder(), is(true));
    }

    @Test
    public void shouldRoundTripMultiStepWithWebhookAndForwardResponder() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook"))
                    .withBlocking(true)
                    .withTimeout(seconds(5))
                    .withFailurePolicy(FailurePolicy.FAIL_FAST),
                ExpectationStep.step()
                    .withHttpForward(forward().withHost("backend.example.com").withPort(8080))
                    .withResponder(true)
            );

        // when
        String json = serializer.serialize(expectation);
        Expectation parsed = serializer.deserialize(json);

        // then
        List<ExpectationStep> steps = parsed.getSteps();
        assertThat(steps, hasSize(2));

        ExpectationStep webhookStep = steps.get(0);
        assertThat(webhookStep.getHttpRequest().getPath().getValue(), is("/webhook"));
        assertThat(webhookStep.getBlocking(), is(true));
        assertThat(webhookStep.getTimeout(), is(seconds(5)));
        assertThat(webhookStep.getFailurePolicy(), is(FailurePolicy.FAIL_FAST));
        assertThat(webhookStep.getResponder(), is(nullValue()));

        ExpectationStep forwardStep = steps.get(1);
        assertThat(forwardStep.getHttpForward().getHost(), is("backend.example.com"));
        assertThat(forwardStep.getHttpForward().getPort(), is(8080));
        assertThat(forwardStep.getResponder(), is(true));
    }

    @Test
    public void shouldRoundTripStepsWithForwardReplace() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/side-effect")),
                ExpectationStep.step()
                    .withHttpOverrideForwardedRequest(
                        HttpOverrideForwardedRequest.forwardOverriddenRequest()
                            .withRequestOverride(request().withPath("/override"))
                    )
                    .withResponder(true),
                ExpectationStep.step()
                    .withHttpRequest(request("/after-webhook"))
            );

        // when
        String json = serializer.serialize(expectation);
        Expectation parsed = serializer.deserialize(json);

        // then
        List<ExpectationStep> steps = parsed.getSteps();
        assertThat(steps, hasSize(3));
        assertThat(steps.get(0).getHttpRequest().getPath().getValue(), is("/side-effect"));
        assertThat(steps.get(1).getHttpOverrideForwardedRequest().getRequestOverride().getPath().getValue(), is("/override"));
        assertThat(steps.get(1).getResponder(), is(true));
        assertThat(steps.get(2).getHttpRequest().getPath().getValue(), is("/after-webhook"));
    }

    @Test
    public void shouldRoundTripHttpErrorOnlyStep() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpError(HttpError.error().withDropConnection(true))
                    .withResponder(true)
            );

        // when
        Expectation parsed = serializer.deserialize(serializer.serialize(expectation));

        // then
        List<ExpectationStep> steps = parsed.getSteps();
        assertThat(steps, hasSize(1));
        assertThat(steps.get(0).getHttpError().getDropConnection(), is(true));
        assertThat(steps.get(0).getResponder(), is(true));
    }

    @Test
    public void shouldPreserveBeforeActionsWhenNoSteps() {
        // given - existing beforeActions style (backward compat)
        Expectation expectation = new Expectation(request("/path"))
            .withBeforeActions(
                AfterAction.beforeAction()
                    .withHttpRequest(request("/hook"))
                    .withBlocking(true)
                    .withTimeout(seconds(2))
                    .withFailurePolicy(FailurePolicy.FAIL_FAST)
            )
            .thenRespond(response("ok"));

        // when
        String json = serializer.serialize(expectation);
        Expectation parsed = serializer.deserialize(json);

        // then - beforeActions preserved, no steps
        assertThat(parsed.getBeforeActions(), hasSize(1));
        assertThat(parsed.getBeforeActions().get(0).getHttpRequest().getPath().getValue(), is("/hook"));
        assertThat(parsed.getSteps(), is(nullValue()));
    }

    @Test
    public void shouldOmitStepsFromJsonWhenNull() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .thenRespond(response("ok"));

        // when
        String json = serializer.serialize(expectation);

        // then
        assertThat(json, not(containsString("\"steps\"")));
    }

    @Test
    public void shouldRoundTripStepsWithDelay() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook"))
                    .withDelay(seconds(1)),
                ExpectationStep.step()
                    .withHttpResponse(response("ok"))
                    .withResponder(true)
            );

        // when
        Expectation parsed = serializer.deserialize(serializer.serialize(expectation));

        // then
        assertThat(parsed.getSteps().get(0).getDelay(), is(seconds(1)));
    }

    @Test
    public void shouldRoundTripStepsWithClassCallback() {
        // given
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpClassCallback(HttpClassCallback.callback("com.example.MyCallback"))
                    .withResponder(true)
            );

        // when
        Expectation parsed = serializer.deserialize(serializer.serialize(expectation));

        // then
        assertThat(parsed.getSteps(), hasSize(1));
        assertThat(parsed.getSteps().get(0).getHttpClassCallback().getCallbackClass(), is("com.example.MyCallback"));
        assertThat(parsed.getSteps().get(0).getResponder(), is(true));
    }

    @Test
    public void shouldPreserveEqualityAfterRoundTrip() {
        // given
        Expectation original = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook")),
                ExpectationStep.step()
                    .withHttpResponse(response("ok"))
                    .withResponder(true)
            );

        // when
        Expectation parsed = serializer.deserialize(serializer.serialize(original));

        // then - structural equality (id is excluded from equals)
        assertThat(parsed.getSteps(), is(original.getSteps()));
    }
}
