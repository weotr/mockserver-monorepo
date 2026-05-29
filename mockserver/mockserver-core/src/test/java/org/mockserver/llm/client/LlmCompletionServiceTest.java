package org.mockserver.llm.client;

import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpResponse.response;

public class LlmCompletionServiceTest {

    private static ParsedConversation prompt() {
        return ParsedConversation.of(Collections.singletonList(
            new ParsedMessage(ParsedMessage.Role.USER, "hello", null, null)));
    }

    private static LlmBackend ollama() {
        return LlmBackend.of(Provider.OLLAMA, null);
    }

    @Test
    public void returnsCompletionOnSuccess() {
        LlmTransport transport = (request, timeout) -> response().withStatusCode(200)
            .withBody("{\"message\":{\"content\":\"hi there\"}}");
        Optional<Completion> result = new LlmCompletionService(transport).complete(ollama(), prompt());
        assertThat(result.isPresent(), is(true));
        assertThat(result.get().getText(), is("hi there"));
    }

    @Test
    public void failsClosedOnTransportException() {
        LlmTransport transport = (request, timeout) -> {
            throw new RuntimeException("connection refused");
        };
        assertThat(new LlmCompletionService(transport).complete(ollama(), prompt()).isPresent(), is(false));
    }

    @Test
    public void failsClosedOnNon2xx() {
        LlmTransport transport = (request, timeout) -> response().withStatusCode(429).withBody("rate limited");
        assertThat(new LlmCompletionService(transport).complete(ollama(), prompt()).isPresent(), is(false));
    }

    @Test
    public void failsClosedOn3xxRedirect() {
        LlmTransport transport = (request, timeout) -> response().withStatusCode(302).withBody("");
        assertThat(new LlmCompletionService(transport).complete(ollama(), prompt()).isPresent(), is(false));
    }

    @Test
    public void failsClosedOnNullResponse() {
        LlmTransport transport = (request, timeout) -> null;
        assertThat(new LlmCompletionService(transport).complete(ollama(), prompt()).isPresent(), is(false));
    }

    @Test
    public void returnsEmptyWhenNoClientRegisteredForProvider() {
        // an empty registry (the static block only populates the singleton)
        LlmClientRegistry emptyRegistry = new LlmClientRegistry();
        LlmTransport transport = (request, timeout) -> response().withStatusCode(200).withBody("{}");
        Optional<Completion> result = new LlmCompletionService(transport, emptyRegistry).complete(ollama(), prompt());
        assertThat(result.isPresent(), is(false));
    }

    @Test
    public void cachesResponseByPromptWithinRun() {
        AtomicInteger calls = new AtomicInteger();
        LlmTransport transport = (request, timeout) -> {
            calls.incrementAndGet();
            return response().withStatusCode(200).withBody("{\"message\":{\"content\":\"cached\"}}");
        };
        LlmCompletionService service = new LlmCompletionService(transport);
        service.complete(ollama(), prompt());
        service.complete(ollama(), prompt());
        assertThat(calls.get(), is(1));
    }

    @Test
    public void returnsEmptyForNullBackend() {
        LlmTransport transport = (request, timeout) -> response().withStatusCode(200).withBody("{}");
        assertThat(new LlmCompletionService(transport).complete(null, prompt()).isPresent(), is(false));
    }
}
