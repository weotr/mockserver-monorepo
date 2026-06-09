package org.mockserver.mock.breakpoint;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class BreakpointDecisionTest {

    @Test
    public void shouldCreateContinueDecision() {
        BreakpointDecision decision = BreakpointDecision.continueOriginal();
        assertThat(decision.getAction(), is(BreakpointDecision.Action.CONTINUE));
        assertThat(decision.getModifiedRequest(), is(nullValue()));
        assertThat(decision.getAbortResponse(), is(nullValue()));
    }

    @Test
    public void shouldCreateModifyDecision() {
        HttpRequest modified = request().withMethod("POST").withPath("/modified");
        BreakpointDecision decision = BreakpointDecision.modify(modified);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedRequest(), is(modified));
        assertThat(decision.getAbortResponse(), is(nullValue()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullModifiedRequest() {
        BreakpointDecision.modify(null);
    }

    @Test
    public void shouldCreateAbortDecisionWithResponse() {
        HttpResponse abortResp = response().withStatusCode(503);
        BreakpointDecision decision = BreakpointDecision.abort(abortResp);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.ABORT));
        assertThat(decision.getModifiedRequest(), is(nullValue()));
        assertThat(decision.getAbortResponse().getStatusCode(), is(503));
    }

    @Test
    public void shouldCreateAbortDecisionWithNullResponse() {
        BreakpointDecision decision = BreakpointDecision.abort(null);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.ABORT));
        assertThat(decision.getAbortResponse(), is(nullValue()));
    }

    @Test
    public void shouldCreateModifyResponseDecision() {
        HttpResponse modified = response().withStatusCode(201).withBody("replaced");
        BreakpointDecision decision = BreakpointDecision.modifyResponse(modified);
        assertThat(decision.getAction(), is(BreakpointDecision.Action.MODIFY));
        assertThat(decision.getModifiedResponse(), is(modified));
        assertThat(decision.getModifiedRequest(), is(nullValue()));
        assertThat(decision.getAbortResponse(), is(nullValue()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullModifiedResponse() {
        BreakpointDecision.modifyResponse(null);
    }
}
