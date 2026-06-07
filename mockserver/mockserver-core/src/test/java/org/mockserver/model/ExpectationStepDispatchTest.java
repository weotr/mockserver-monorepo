package org.mockserver.model;

import org.junit.Test;
import org.mockserver.mock.Expectation;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the dispatch-related logic of the steps model:
 * <ul>
 *   <li>Primary action resolution from the responder step</li>
 *   <li>Pre-responder step extraction (ordered)</li>
 *   <li>Post-responder step extraction (ordered)</li>
 *   <li>Backward compat: expectations without steps use existing path</li>
 * </ul>
 */
public class ExpectationStepDispatchTest {

    @Test
    public void shouldResolvePrimaryActionFromResponderStep() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpRequest(request("/webhook")),
                ExpectationStep.step()
                    .withHttpResponse(response("from-step"))
                    .withResponder(true)
            );

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpResponse.class)));
        assertThat(((HttpResponse) action).getBodyAsString(), is("from-step"));
        assertThat(action.getType(), is(Action.Type.RESPONSE));
    }

    @Test
    public void shouldResolvePrimaryActionFromForwardResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpForward(forward().withHost("example.com"))
                    .withResponder(true)
            );

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpForward.class)));
        assertThat(((HttpForward) action).getHost(), is("example.com"));
        assertThat(action.getType(), is(Action.Type.FORWARD));
    }

    @Test
    public void shouldResolvePrimaryActionFromForwardReplaceResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpOverrideForwardedRequest(
                        HttpOverrideForwardedRequest.forwardOverriddenRequest()
                            .withRequestOverride(request().withPath("/override"))
                    )
                    .withResponder(true)
            );

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpOverrideForwardedRequest.class)));
        assertThat(action.getType(), is(Action.Type.FORWARD_REPLACE));
    }

    @Test
    public void shouldResolvePrimaryActionFromHttpError() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpError(HttpError.error().withDropConnection(true))
                    .withResponder(true)
            );

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpError.class)));
        assertThat(action.getType(), is(Action.Type.ERROR));
    }

    @Test
    public void shouldExtractPreResponderStepsInOrder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpRequest(request("/step1")),
                ExpectationStep.step().withHttpRequest(request("/step2")),
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true),
                ExpectationStep.step().withHttpRequest(request("/step4"))
            );

        List<ExpectationStep> pre = expectation.getPreResponderSteps();
        assertThat(pre, hasSize(2));
        assertThat(pre.get(0).getHttpRequest().getPath().getValue(), is("/step1"));
        assertThat(pre.get(1).getHttpRequest().getPath().getValue(), is("/step2"));
    }

    @Test
    public void shouldExtractPostResponderStepsInOrder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpRequest(request("/step1")),
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true),
                ExpectationStep.step().withHttpRequest(request("/step3")),
                ExpectationStep.step().withHttpRequest(request("/step4"))
            );

        List<ExpectationStep> post = expectation.getPostResponderSteps();
        assertThat(post, hasSize(2));
        assertThat(post.get(0).getHttpRequest().getPath().getValue(), is("/step3"));
        assertThat(post.get(1).getHttpRequest().getPath().getValue(), is("/step4"));
    }

    @Test
    public void shouldReturnEmptyPreStepsWhenResponderIsFirst() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true),
                ExpectationStep.step().withHttpRequest(request("/after"))
            );

        assertThat(expectation.getPreResponderSteps(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyPostStepsWhenResponderIsLast() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpRequest(request("/before")),
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true)
            );

        assertThat(expectation.getPostResponderSteps(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyListsWhenNoSteps() {
        Expectation expectation = new Expectation(request("/path"))
            .thenRespond(response("ok"));

        assertThat(expectation.getPreResponderSteps(), is(empty()));
        assertThat(expectation.getPostResponderSteps(), is(empty()));
    }

    @Test
    public void shouldUseLegacyPathWhenNoSteps() {
        // backward compat: when steps is null, the traditional primary action is used
        Expectation expectation = new Expectation(request("/path"))
            .thenRespond(response("legacy"));

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpResponse.class)));
        assertThat(((HttpResponse) action).getBodyAsString(), is("legacy"));
    }

    @Test
    public void shouldUseLegacyPathWithBeforeActions() {
        // backward compat: beforeActions + primary still works
        Expectation expectation = new Expectation(request("/path"))
            .withBeforeActions(
                AfterAction.beforeAction().withHttpRequest(request("/hook"))
            )
            .thenRespond(response("legacy-with-before"));

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpResponse.class)));
        assertThat(((HttpResponse) action).getBodyAsString(), is("legacy-with-before"));
        assertThat(expectation.getBeforeActions(), hasSize(1));
    }

    @Test
    public void shouldCloneSteps() {
        Expectation original = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step().withHttpRequest(request("/webhook")),
                ExpectationStep.step().withHttpResponse(response("ok")).withResponder(true)
            );

        Expectation clone = original.clone();
        assertThat(clone.getSteps(), is(original.getSteps()));
        assertThat(clone.getSteps(), is(not(sameInstance(original.getSteps()))));
    }

    @Test
    public void shouldResolvePrimaryActionFromClassCallbackResponder() {
        Expectation expectation = new Expectation(request("/path"))
            .withSteps(
                ExpectationStep.step()
                    .withHttpClassCallback(HttpClassCallback.callback("com.example.Callback"))
                    .withResponder(true)
            );

        Action action = expectation.getPrimaryAction();
        assertThat(action, is(instanceOf(HttpClassCallback.class)));
        assertThat(action.getType(), is(Action.Type.RESPONSE_CLASS_CALLBACK));
    }
}
