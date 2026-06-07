package org.mockserver.model;

import org.junit.Test;
import org.mockserver.mock.Expectation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the validation rules for the unified ordered {@code steps} model
 * on {@link Expectation}:
 * <ul>
 *   <li>Exactly one step must be the responder</li>
 *   <li>{@code httpError} cannot be combined with other steps</li>
 *   <li>Each step must have exactly one action target</li>
 *   <li>Webhooks cannot be responders</li>
 *   <li>Valid multi-forward + one responder passes</li>
 * </ul>
 */
public class ExpectationStepValidationTest {

    @Test
    public void shouldPassValidationWithSingleResponderStep() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpResponse(response("ok"))
                    .withResponder(true)
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldPassValidationWithSideEffectAndResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook")),
                ExpectationStep.step()
                    .withHttpResponse(response("ok"))
                    .withResponder(true)
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldPassValidationWithMultipleForwardsAndOneResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpForward(forward().withHost("side-effect.example.com")),
                ExpectationStep.step()
                    .withHttpForward(forward().withHost("responder.example.com"))
                    .withResponder(true),
                ExpectationStep.step()
                    .withHttpRequest(request("/after-webhook"))
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldPassValidationWithForwardReplaceResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook")),
                ExpectationStep.step()
                    .withHttpOverrideForwardedRequest(
                        HttpOverrideForwardedRequest.forwardOverriddenRequest()
                            .withRequestOverride(request().withPath("/override"))
                    )
                    .withResponder(true)
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldRejectNoResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook1")),
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook2"))
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("exactly one step with responder=true"));
        assertThat(error, containsString("none found"));
    }

    @Test
    public void shouldRejectMultipleResponders() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpResponse(response("first"))
                    .withResponder(true),
                ExpectationStep.step()
                    .withHttpResponse(response("second"))
                    .withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("exactly one step with responder=true"));
        assertThat(error, containsString("found 2"));
    }

    @Test
    public void shouldRejectHttpErrorCombinedWithOtherSteps() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook")),
                ExpectationStep.step()
                    .withHttpError(HttpError.error())
                    .withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("httpError cannot be combined with other steps"));
    }

    @Test
    public void shouldPassValidationWithSoleHttpErrorStep() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpError(HttpError.error())
                    .withResponder(true)
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldRejectStepWithNoTarget() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("step[0] has no action target set"));
    }

    @Test
    public void shouldRejectWebhookAsResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook"))
                    .withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("side-effect-only"));
        assertThat(error, containsString("cannot produce a response"));
    }

    @Test
    public void shouldReturnNullForNoSteps() {
        Expectation expectation = new Expectation(request("/path"))
            .thenRespond(response("ok"));

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldPassWithCallbackResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpRequest(request("/webhook")),
                ExpectationStep.step()
                    .withHttpClassCallback(HttpClassCallback.callback("com.example.MyCallback"))
                    .withResponder(true)
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldPreserveStepOrder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpRequest(request("/step1")),
                ExpectationStep.step().withHttpRequest(request("/step2")),
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true),
                ExpectationStep.step().withHttpRequest(request("/step4"))
            );

        assertThat(expectation.getSteps(), hasSize(4));
        assertThat(expectation.getPreResponderSteps(), hasSize(2));
        assertThat(expectation.getPostResponderSteps(), hasSize(1));
    }

    // --- coexistence validation tests (AMB-04) ---

    @Test
    public void shouldRejectStepsWithBeforeActions() {
        Expectation expectation = new Expectation(request("/path"))
            .withBeforeActions(
                AfterAction.beforeAction().withHttpRequest(request("/hook"))
            )
            .withSteps(
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("steps cannot be combined with beforeActions"));
        assertThat(error, containsString("use steps for the full ordered pipeline"));
    }

    @Test
    public void shouldRejectStepsWithTopLevelHttpResponse() {
        Expectation expectation = new Expectation(request("/path"))
            .thenRespond(response("top-level"))
            .withSteps(
                ExpectationStep.step().withHttpResponse(response("in-step")).withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("steps cannot be combined with a top-level response action"));
        assertThat(error, containsString("responder step defines the action"));
    }

    @Test
    public void shouldRejectStepsWithTopLevelHttpForward() {
        Expectation expectation = new Expectation(request("/path"))
            .thenForward(forward().withHost("example.com"))
            .withSteps(
                ExpectationStep.step().withHttpResponse(response("in-step")).withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("steps cannot be combined with a top-level response action"));
    }

    @Test
    public void shouldRejectStepsWithTopLevelHttpError() {
        Expectation expectation = new Expectation(request("/path"))
            .thenError(HttpError.error())
            .withSteps(
                ExpectationStep.step().withHttpResponse(response("in-step")).withResponder(true)
            );

        String error = expectation.validateSteps();
        assertThat(error, containsString("steps cannot be combined with a top-level response action"));
    }

    @Test
    public void shouldAllowStepsWithAfterActions() {
        Expectation expectation = new Expectation(request("/path"))
            .withAfterActions(
                AfterAction.afterAction().withHttpRequest(request("/audit"))
            )
            .withSteps(
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true)
            );

        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldAllowPlainBeforeActionsWithoutSteps() {
        // backward compat: beforeActions + primary action still works without steps
        Expectation expectation = new Expectation(request("/path"))
            .withBeforeActions(
                AfterAction.beforeAction().withHttpRequest(request("/hook"))
            )
            .thenRespond(response("ok"));

        // validateSteps should return null (no steps present)
        assertThat(expectation.validateSteps(), is(nullValue()));
    }

    @Test
    public void shouldAllowPlainExpectationWithoutStepsOrBeforeActions() {
        // backward compat: plain expectation works unchanged
        Expectation expectation = new Expectation(request("/path"))
            .thenRespond(response("ok"));

        assertThat(expectation.validateSteps(), is(nullValue()));
    }
}
