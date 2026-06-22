package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class LlmErrorBodyTest {

    @Test
    public void anthropicOverloadIs529OverloadedError() {
        LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(Provider.ANTHROPIC, LlmErrorBody.Kind.OVERLOAD);
        assertThat(shape.getStatusCode(), is(529));
        assertThat(shape.getJsonBody(), containsString("\"type\":\"overloaded_error\""));
        assertThat(shape.getJsonBody(), containsString("\"type\":\"error\""));
    }

    @Test
    public void anthropicRateLimitIs429RateLimitError() {
        LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(Provider.ANTHROPIC, LlmErrorBody.Kind.RATE_LIMIT);
        assertThat(shape.getStatusCode(), is(429));
        assertThat(shape.getJsonBody(), containsString("\"type\":\"rate_limit_error\""));
    }

    @Test
    public void openAiRateLimitIs429RateLimitExceeded() {
        LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(Provider.OPENAI, LlmErrorBody.Kind.RATE_LIMIT);
        assertThat(shape.getStatusCode(), is(429));
        assertThat(shape.getJsonBody(), containsString("\"type\":\"rate_limit_exceeded\""));
        assertThat(shape.getJsonBody(), containsString("\"code\":\"rate_limit_exceeded\""));
    }

    @Test
    public void openAiOverloadIs503ServerError() {
        LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(Provider.OPENAI, LlmErrorBody.Kind.OVERLOAD);
        assertThat(shape.getStatusCode(), is(503));
        assertThat(shape.getJsonBody(), containsString("\"type\":\"server_error\""));
    }

    @Test
    public void azureAndResponsesShareOpenAiShape() {
        assertThat(LlmErrorBody.bodyFor(Provider.AZURE_OPENAI, LlmErrorBody.Kind.RATE_LIMIT).getJsonBody(),
            containsString("rate_limit_exceeded"));
        assertThat(LlmErrorBody.bodyFor(Provider.OPENAI_RESPONSES, LlmErrorBody.Kind.OVERLOAD).getJsonBody(),
            containsString("server_error"));
    }

    @Test
    public void geminiUsesGoogleEnvelope() {
        LlmErrorBody.ErrorShape rl = LlmErrorBody.bodyFor(Provider.GEMINI, LlmErrorBody.Kind.RATE_LIMIT);
        assertThat(rl.getStatusCode(), is(429));
        assertThat(rl.getJsonBody(), containsString("\"status\":\"RESOURCE_EXHAUSTED\""));
        LlmErrorBody.ErrorShape ov = LlmErrorBody.bodyFor(Provider.GEMINI, LlmErrorBody.Kind.OVERLOAD);
        assertThat(ov.getStatusCode(), is(503));
        assertThat(ov.getJsonBody(), containsString("\"status\":\"UNAVAILABLE\""));
    }

    @Test
    public void bedrockUsesAwsErrorEnvelope() {
        LlmErrorBody.ErrorShape rl = LlmErrorBody.bodyFor(Provider.BEDROCK, LlmErrorBody.Kind.RATE_LIMIT);
        assertThat(rl.getStatusCode(), is(429));
        assertThat(rl.getJsonBody(), containsString("\"__type\":\"ThrottlingException\""));
        LlmErrorBody.ErrorShape ov = LlmErrorBody.bodyFor(Provider.BEDROCK, LlmErrorBody.Kind.OVERLOAD);
        assertThat(ov.getStatusCode(), is(503));
        assertThat(ov.getJsonBody(), containsString("ServiceUnavailableException"));
    }

    @Test
    public void ollamaUsesSimpleErrorBody() {
        LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(Provider.OLLAMA, LlmErrorBody.Kind.SERVER_ERROR);
        assertThat(shape.getStatusCode(), is(500));
        assertThat(shape.getJsonBody(), containsString("\"error\""));
    }

    @Test
    public void nullProviderReturnsNullSoCallerFallsBackToGeneric() {
        assertThat(LlmErrorBody.bodyFor(null, LlmErrorBody.Kind.OVERLOAD), is(nullValue()));
    }
}
