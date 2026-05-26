package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

public class HttpLlmResponseActionHandlerTest {

    @Test
    public void shouldReturn200ForRegisteredCodec() {
        // given — ANTHROPIC codec is registered
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — registered codecs return 200
        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void shouldReturn200ForAllRegisteredProviders() {
        // After M4 every Provider enum value has a registered codec, so the
        // handler must return 200 for all of them. The "unregistered provider"
        // safety-net path is still reachable in code (e.g., during a future
        // milestone that adds a new Provider value without a codec) but cannot
        // be exercised from production paths today.
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/test");

        for (Provider provider : Provider.values()) {
            HttpLlmResponse llmResponse = llmResponse()
                .withProvider(provider)
                .withCompletion(completion().withText("test"));

            HttpResponse response = handler.handle(llmResponse, request);

            assertThat("expected 200 for registered provider " + provider,
                response.getStatusCode(), is(200));
        }
    }

    @Test
    public void shouldReturn400WhenProviderIsNull() {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("null"));
    }
}
