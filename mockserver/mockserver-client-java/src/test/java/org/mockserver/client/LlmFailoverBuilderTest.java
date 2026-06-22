package org.mockserver.client;

import org.junit.Test;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.client.LlmFailoverBuilder.llmFailover;
import static org.mockserver.model.Completion.completion;

public class LlmFailoverBuilderTest {

    @Test
    public void shouldBuildFailoverWithTwoFailuresAndSuccess() {
        // given/when
        Expectation[] expectations = llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .failWith(503)
            .failWith(503)
            .thenRespondWith(completion().withText("The answer is 42."))
            .build();

        // then — two consecutive 503s coalesce into one expectation + one success
        assertThat(expectations.length, is(2));

        // Failure expectation: HTTP response with Times.exactly(2)
        Expectation failExp = expectations[0];
        assertThat(failExp.getHttpResponse(), is(notNullValue()));
        assertThat(failExp.getHttpResponse().getStatusCode(), is(503));
        assertThat(failExp.getTimes().getRemainingTimes(), is(2));
        assertThat(failExp.getTimes().isUnlimited(), is(false));
        HttpRequest failRequest = (HttpRequest) failExp.getHttpRequest();
        assertThat(failRequest.getPath().getValue(), is("/v1/chat/completions"));
        assertThat(failRequest.getMethod().getValue(), is("POST"));
        // Verify the body contains a plausible error
        assertThat(failExp.getHttpResponse().getBodyAsString(), containsString("service_unavailable"));

        // Success expectation: LLM response with Times.unlimited()
        Expectation successExp = expectations[1];
        assertThat(successExp.getHttpLlmResponse(), is(notNullValue()));
        assertThat(successExp.getHttpLlmResponse().getProvider(), is(Provider.OPENAI));
        assertThat(successExp.getHttpLlmResponse().getModel(), is("gpt-4o"));
        assertThat(successExp.getHttpLlmResponse().getCompletion().getText(), is("The answer is 42."));
        assertThat(successExp.getTimes().isUnlimited(), is(true));
    }

    @Test
    public void shouldBuildFailoverWithDifferentStatusCodes() {
        // given/when
        Expectation[] expectations = llmFailover()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .failWith(503)
            .failWith(429)
            .failWith(500)
            .thenRespondWith(completion().withText("Hello"))
            .build();

        // then — three different statuses = three failure expectations + one success
        assertThat(expectations.length, is(4));

        assertThat(expectations[0].getHttpResponse().getStatusCode(), is(503));
        assertThat(expectations[0].getTimes().getRemainingTimes(), is(1));

        assertThat(expectations[1].getHttpResponse().getStatusCode(), is(429));
        assertThat(expectations[1].getTimes().getRemainingTimes(), is(1));
        assertThat(expectations[1].getHttpResponse().getBodyAsString(), containsString("rate_limit_error"));

        assertThat(expectations[2].getHttpResponse().getStatusCode(), is(500));
        assertThat(expectations[2].getTimes().getRemainingTimes(), is(1));
        assertThat(expectations[2].getHttpResponse().getBodyAsString(), containsString("internal_server_error"));

        // Success
        assertThat(expectations[3].getHttpLlmResponse(), is(notNullValue()));
        assertThat(expectations[3].getTimes().isUnlimited(), is(true));
    }

    @Test
    public void shouldCoalesceConsecutiveSameStatusFailures() {
        // given/when
        Expectation[] expectations = llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .failWith(503)
            .failWith(503)
            .failWith(503)
            .failWith(429)
            .failWith(429)
            .thenRespondWith(completion().withText("ok"))
            .build();

        // then — 3x503 coalesced + 2x429 coalesced + success = 3 expectations
        assertThat(expectations.length, is(3));

        assertThat(expectations[0].getHttpResponse().getStatusCode(), is(503));
        assertThat(expectations[0].getTimes().getRemainingTimes(), is(3));

        assertThat(expectations[1].getHttpResponse().getStatusCode(), is(429));
        assertThat(expectations[1].getTimes().getRemainingTimes(), is(2));

        assertThat(expectations[2].getHttpLlmResponse(), is(notNullValue()));
        assertThat(expectations[2].getTimes().isUnlimited(), is(true));
    }

    @Test
    public void shouldBuildFailoverWithCustomErrorBody() {
        // given
        String customBody = "{\"error\":\"custom\"}";

        // when
        Expectation[] expectations = llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .failWith(503, customBody)
            .thenRespondWith(completion().withText("ok"))
            .build();

        // then
        assertThat(expectations.length, is(2));
        assertThat(expectations[0].getHttpResponse().getBodyAsString(), is(customBody));
    }

    @Test
    public void shouldBuildFailoverWithCountOverload() {
        // given/when
        Expectation[] expectations = llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .failWith(503, 5)
            .thenRespondWith(completion().withText("ok"))
            .build();

        // then — 5 consecutive 503s coalesce into one expectation
        assertThat(expectations.length, is(2));
        assertThat(expectations[0].getHttpResponse().getStatusCode(), is(503));
        assertThat(expectations[0].getTimes().getRemainingTimes(), is(5));
    }

    @Test
    public void shouldNotCoalesceNonConsecutiveSameStatus() {
        // given/when — 503, 429, 503 should NOT coalesce the two 503s
        Expectation[] expectations = llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .failWith(503)
            .failWith(429)
            .failWith(503)
            .thenRespondWith(completion().withText("ok"))
            .build();

        // then — 3 separate failure expectations + success
        assertThat(expectations.length, is(4));
        assertThat(expectations[0].getHttpResponse().getStatusCode(), is(503));
        assertThat(expectations[0].getTimes().getRemainingTimes(), is(1));
        assertThat(expectations[1].getHttpResponse().getStatusCode(), is(429));
        assertThat(expectations[1].getTimes().getRemainingTimes(), is(1));
        assertThat(expectations[2].getHttpResponse().getStatusCode(), is(503));
        assertThat(expectations[2].getTimes().getRemainingTimes(), is(1));
        assertThat(expectations[3].getHttpLlmResponse(), is(notNullValue()));
    }

    @Test
    public void shouldSetContentTypeOnFailureResponse() {
        Expectation[] expectations = llmFailover()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .failWith(429)
            .thenRespondWith(completion().withText("ok"))
            .build();

        assertThat(expectations[0].getHttpResponse().getFirstHeader("Content-Type"),
            is("application/json"));
    }

    @Test
    public void shouldUseProviderOnSuccessExpectation() {
        Expectation[] expectations = llmFailover()
            .withPath("/v1/messages")
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .failWith(503)
            .thenRespondWith(completion()
                .withText("response")
                .withStopReason("end_turn"))
            .build();

        HttpLlmResponse llmResponse = expectations[1].getHttpLlmResponse();
        assertThat(llmResponse.getProvider(), is(Provider.ANTHROPIC));
        assertThat(llmResponse.getModel(), is("claude-sonnet-4-20250514"));
        assertThat(llmResponse.getCompletion().getStopReason(), is("end_turn"));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenPathMissing() {
        llmFailover()
            .withProvider(Provider.OPENAI)
            .failWith(503)
            .thenRespondWith(completion().withText("ok"))
            .build();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenProviderMissing() {
        llmFailover()
            .withPath("/v1/chat/completions")
            .failWith(503)
            .thenRespondWith(completion().withText("ok"))
            .build();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenNoFailuresDefined() {
        llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .thenRespondWith(completion().withText("ok"))
            .build();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenSuccessCompletionMissing() {
        llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .failWith(503)
            .build();
    }

    @Test
    public void shouldGenerateDefaultErrorBodiesForKnownStatuses() {
        assertThat(LlmFailoverBuilder.defaultErrorBody(429), containsString("rate_limit_error"));
        assertThat(LlmFailoverBuilder.defaultErrorBody(500), containsString("internal_server_error"));
        assertThat(LlmFailoverBuilder.defaultErrorBody(502), containsString("bad_gateway"));
        assertThat(LlmFailoverBuilder.defaultErrorBody(503), containsString("service_unavailable"));
        assertThat(LlmFailoverBuilder.defaultErrorBody(418), containsString("\"error\""));
    }

    @Test
    public void shouldPreserveCustomAndDefaultBodiesInMixedScenario() {
        String customBody = "{\"custom\":true}";
        Expectation[] expectations = llmFailover()
            .withPath("/v1/chat/completions")
            .withProvider(Provider.OPENAI)
            .failWith(503, customBody)
            .failWith(503) // default body — different from custom, so not coalesced
            .thenRespondWith(completion().withText("ok"))
            .build();

        // Custom and default bodies differ, so they are NOT coalesced
        assertThat(expectations.length, is(3));
        assertThat(expectations[0].getHttpResponse().getBodyAsString(), is(customBody));
        assertThat(expectations[1].getHttpResponse().getBodyAsString(), containsString("service_unavailable"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectOutOfRangeStatusCode() {
        llmFailover().failWith(700);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTooLowStatusCode() {
        llmFailover().failWith(99);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNonPositiveCount() {
        llmFailover().failWith(503, 0);
    }
}
