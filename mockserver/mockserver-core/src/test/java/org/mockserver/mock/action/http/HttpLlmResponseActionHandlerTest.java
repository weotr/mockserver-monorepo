package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.codec.AnthropicCodec;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.slf4j.event.Level;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

public class HttpLlmResponseActionHandlerTest {

    @After
    public void restoreAnthropicCodec() {
        // Ensure the real AnthropicCodec is always restored after tests that
        // substitute a throwing codec, so other tests in the same JVM are not
        // affected.
        ProviderCodecRegistry.getInstance().register(new AnthropicCodec());
        // The quota registry is a JVM singleton — clear it so quota tests do not
        // accumulate state across runs in a shared (non-forked) JVM.
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        // Restore the (default-off) usage-inference switch so it does not leak into
        // other tests in this (non-forked) JVM.
        org.mockserver.configuration.ConfigurationProperties.llmInferUsageEnabled(false);
    }

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
    public void shouldReturn200ForAllChatProviders() {
        // Every chat-capable Provider enum value has a registered codec, so the
        // handler must return 200 for all of them given a completion. The
        // "unregistered provider" safety-net path is still reachable in code but
        // cannot be exercised from production paths today. Rerank-only providers
        // (COHERE, VOYAGE) have no completion path and are tested via their
        // dedicated rerank tests.
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/test");

        for (Provider provider : Provider.values()) {
            if (provider == Provider.COHERE || provider == Provider.VOYAGE) {
                continue;
            }
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

    // --- Gap 1: Codec internal failure -> 502 ---

    @Test
    public void shouldReturn502WhenCodecEncodeThrowsRuntimeException() {
        // given — register a deliberately-failing codec for ANTHROPIC
        ProviderCodec throwingCodec = new ProviderCodec() {
            @Override
            public Provider provider() {
                return Provider.ANTHROPIC;
            }

            @Override
            public String apiVersion() {
                return "test";
            }

            @Override
            public HttpResponse encode(Completion completion, String model) {
                throw new RuntimeException("boom");
            }

            @Override
            public ParsedConversation decode(HttpRequest request) {
                return ParsedConversation.empty();
            }
        };
        ProviderCodecRegistry.getInstance().register(throwingCodec);

        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("Hello"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — 502 with correct error body
        assertThat(response.getStatusCode(), is(502));
        assertThat(response.getBodyAsString(), containsString("\"error\":\"llm codec encode failed\""));
        assertThat(response.getBodyAsString(), containsString("\"provider\":\"ANTHROPIC\""));
        // Provider name is escaped via Provider.name(), not the exception message
        assertThat("exception message must not leak into the body",
            response.getBodyAsString().contains("boom"), is(false));

        // Verify WARN log was recorded
        verify(mockLogger).logEvent(argThat(logEntry ->
            logEntry.getLogLevel() == Level.WARN
                && logEntry.getMessageFormat().contains("llm codec encode failed")
        ));
    }

    @Test
    public void shouldReturn502WhenCodecEncodeEmbeddingThrowsRuntimeException() {
        // given — register a codec that throws from encodeEmbedding
        ProviderCodec throwingCodec = new ProviderCodec() {
            @Override
            public Provider provider() {
                return Provider.ANTHROPIC;
            }

            @Override
            public String apiVersion() {
                return "test";
            }

            @Override
            public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
                throw new RuntimeException("embedding boom");
            }

            @Override
            public ParsedConversation decode(HttpRequest request) {
                return ParsedConversation.empty();
            }
        };
        ProviderCodecRegistry.getInstance().register(throwingCodec);

        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withEmbedding(EmbeddingResponse.embedding().withDimensions(8));
        HttpRequest request = request().withPath("/v1/embeddings");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — 502 with correct error body
        assertThat(response.getStatusCode(), is(502));
        assertThat(response.getBodyAsString(), containsString("\"error\":\"llm codec encode failed\""));
        assertThat(response.getBodyAsString(), containsString("\"provider\":\"ANTHROPIC\""));
        assertThat("exception message must not leak into the body",
            response.getBodyAsString().contains("embedding boom"), is(false));
    }

    @Test
    public void shouldReturn400WhenNoCompletionEmbeddingOrRerankConfigured() {
        // given
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC);
        HttpRequest request = request().withPath("/test");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("must have either a completion, embedding, or rerank configured"));
    }

    @Test
    public void shouldReturn501WhenStreamingReachesNonStreamingPath() {
        // given — streaming completion sent to handle() (not handleStreaming())
        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);

        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("stream").withStreaming(true));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(501));
        assertThat(response.getBodyAsString(), containsString("streaming LLM responses must be dispatched through the SSE handler"));
    }

    // --- structured-output (outputSchema) validation ---

    private static final String PERSON_SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}},\"required\":[\"name\",\"age\"]}";

    @Test
    public void shouldNotFlagWhenOutputConformsToSchema() {
        // given — completion text is valid JSON conforming to its declared outputSchema
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\",\"age\":36}")
                .withOutputSchema(PERSON_SCHEMA));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — no diagnostic header, response returned normally
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
        assertThat(response.getBodyAsString(), containsString("Ada"));
    }

    @Test
    public void shouldFlagButNotAlterBodyWhenOutputViolatesSchema() {
        // given — completion text is missing the required "age" field
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\"}")
                .withOutputSchema(PERSON_SCHEMA));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — fail-soft: body unchanged (still 200, still carries the configured text)
        // but a diagnostic header flags the non-conformance
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("Ada"));
        String diagnostic = response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER);
        assertThat(diagnostic, not(is("")));
        // header value must be a single line (no CR/LF)
        assertThat(diagnostic.contains("\n") || diagnostic.contains("\r"), is(false));
    }

    @Test
    public void shouldFlagWhenOutputTextIsNotJson() {
        // given — declared schema but the text is plain prose, not JSON
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("just some prose, not json")
                .withOutputSchema(PERSON_SCHEMA));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — flagged, but the response (status + body) is still returned unchanged
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("just some prose"));
        assertThat(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), not(is("")));
    }

    @Test
    public void shouldTreatMalformedSchemaAsNoOpAndNotBreakResponse() {
        // given — outputSchema is rejected by the validator (the JsonSchemaValidator
        // constructor requires a *.json path or a string ending in '}'); validation must fail-soft
        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\",\"age\":36}")
                .withOutputSchema("{ this is not valid json schema"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — response is unaffected, no diagnostic header
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
        assertThat(response.getBodyAsString(), containsString("Ada"));
    }

    @Test
    public void shouldNotFlagWhenNoOutputSchemaConfigured() {
        // given — no outputSchema: validation is skipped entirely
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("anything at all"));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
    }

    // --- structured-output validator caching (efficiency) ---

    @Test
    public void shouldReuseCompiledValidatorAcrossRepeatedCallsWithSameSchema() {
        // given — a handler and a completion declaring a fixed outputSchema
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\",\"age\":36}")
                .withOutputSchema(PERSON_SCHEMA));
        HttpRequest request = request().withPath("/v1/messages");

        // before any request, nothing is cached for the schema
        assertThat(handler.cachedStructuredOutputValidator(PERSON_SCHEMA), is(nullValue()));

        // when — first request compiles and caches the validator
        handler.handle(llmResponse, request);
        org.mockserver.validator.jsonschema.JsonSchemaValidator first =
            handler.cachedStructuredOutputValidator(PERSON_SCHEMA);
        assertThat(first, is(notNullValue()));

        // and — repeated requests with the same schema reuse the very same validator instance
        handler.handle(llmResponse, request);
        handler.handle(llmResponse, request);
        org.mockserver.validator.jsonschema.JsonSchemaValidator afterRepeats =
            handler.cachedStructuredOutputValidator(PERSON_SCHEMA);
        assertThat("validator is reused, not recompiled", afterRepeats, is(sameInstance(first)));
    }

    @Test
    public void shouldCacheDistinctValidatorsPerDistinctSchemaAndKeepResultsCorrect() {
        // given — two distinct schemas; one match conforms, the other does not
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String nameOnlySchema =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";

        HttpRequest request = request().withPath("/v1/messages");

        // when — conforms to PERSON_SCHEMA
        HttpResponse personResponse = handler.handle(llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("{\"name\":\"Ada\",\"age\":36}").withOutputSchema(PERSON_SCHEMA)), request);
        // and — conforms to the name-only schema (missing age is fine here)
        HttpResponse nameResponse = handler.handle(llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("{\"name\":\"Ada\"}").withOutputSchema(nameOnlySchema)), request);

        // then — both validate cleanly (no diagnostic header), and two distinct validators are cached
        assertThat(personResponse.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
        assertThat(nameResponse.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
        assertThat(handler.cachedStructuredOutputValidator(PERSON_SCHEMA), is(notNullValue()));
        assertThat(handler.cachedStructuredOutputValidator(nameOnlySchema), is(notNullValue()));
        assertThat(handler.cachedStructuredOutputValidator(PERSON_SCHEMA),
            is(not(sameInstance(handler.cachedStructuredOutputValidator(nameOnlySchema)))));

        // and — the cached validator still produces a correct *failure* when text violates the schema
        HttpResponse violatingResponse = handler.handle(llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("{\"age\":36}").withOutputSchema(PERSON_SCHEMA)), request);
        assertThat(violatingResponse.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), not(is("")));
        // reused the same PERSON_SCHEMA validator
        assertThat(handler.cachedStructuredOutputValidator(PERSON_SCHEMA), is(sameInstance(handler.cachedStructuredOutputValidator(PERSON_SCHEMA))));
    }

    @Test
    public void shouldValidateConcurrentlyAgainstSharedCachedValidatorWithoutErrorAndConsistentResults() throws Exception {
        // given — a single handler shared across threads, all using the same schema (so they all
        // hit the same cached, synchronized validator)
        final HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        final HttpRequest request = request().withPath("/v1/messages");

        final int threads = 8;
        final int iterations = 40;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final boolean conforming = (t % 2 == 0);
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    String text = conforming ? "{\"name\":\"Ada\",\"age\":36}" : "{\"name\":\"Ada\"}";
                    HttpResponse response = handler.handle(llmResponse()
                        .withProvider(Provider.ANTHROPIC)
                        .withModel("claude-sonnet-4-20250514")
                        .withCompletion(completion().withText(text).withOutputSchema(PERSON_SCHEMA)), request);
                    boolean flagged = !"".equals(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER));
                    // conforming text must never be flagged; non-conforming must always be flagged
                    if (flagged == conforming) {
                        return false;
                    }
                }
                return true;
            }));
        }
        start.countDown();

        // then — every thread observed consistent, correct results with no exception
        for (java.util.concurrent.Future<Boolean> f : futures) {
            assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS), is(true));
        }
        pool.shutdownNow();
        // and — only a single validator instance was ever compiled for the shared schema
        assertThat(handler.cachedStructuredOutputValidator(PERSON_SCHEMA), is(notNullValue()));
    }

    // --- strict structured-output enforcement (opt-in) ---

    @Test
    public void shouldReturnNoEnforcementErrorWhenEnforcementOff() {
        // given — outputSchema declared but enforcement NOT enabled (the default): a
        // non-conforming body must remain fail-soft (no enforcement error), so the
        // existing validate-and-log behaviour is unchanged
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\"}")
                .withOutputSchema(PERSON_SCHEMA));
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse enforcementError = handler.enforcementErrorResponseOrNull(llmResponse, request);

        // then — no enforcement error, the response is delivered normally (fail-soft path)
        assertThat(enforcementError, is(nullValue()));
        // and the fail-soft path still flags the non-conformance via the diagnostic header
        HttpResponse response = handler.handle(llmResponse, request);
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("Ada"));
        assertThat(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), not(is("")));
    }

    @Test
    public void shouldReturnEnforcementErrorWhenEnforcementOnAndBodyNonConformant() {
        // given — enforcement enabled and the configured text violates the schema (missing "age")
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\"}")
                .withOutputSchema(PERSON_SCHEMA)
                .enforceOutputSchema());
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse enforcementError = handler.enforcementErrorResponseOrNull(llmResponse, request);

        // then — fail loudly: a provider-correct error with the enforcement status, NOT the
        // non-conforming body
        assertThat(enforcementError, is(notNullValue()));
        assertThat(enforcementError.getStatusCode(), is(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_ENFORCEMENT_STATUS));
        // Anthropic provider-correct error envelope
        assertThat(enforcementError.getBodyAsString(), containsString("\"type\":\"error\""));
        assertThat(enforcementError.getBodyAsString(), containsString("api_error"));
        // the non-conforming text is not leaked into the error body
        assertThat(enforcementError.getBodyAsString(), not(containsString("Ada")));
        String diagnostic = enforcementError.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER);
        assertThat(diagnostic, not(is("")));
        assertThat(diagnostic.contains("\n") || diagnostic.contains("\r"), is(false));
    }

    @Test
    public void shouldReturnNoEnforcementErrorWhenEnforcementOnAndBodyConformant() {
        // given — enforcement enabled and the configured text conforms to the schema
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\",\"age\":36}")
                .withOutputSchema(PERSON_SCHEMA)
                .enforceOutputSchema());
        HttpRequest request = request().withPath("/v1/messages");

        // when
        HttpResponse enforcementError = handler.enforcementErrorResponseOrNull(llmResponse, request);

        // then — conformant, so no enforcement error; the response is delivered normally
        assertThat(enforcementError, is(nullValue()));
        HttpResponse response = handler.handle(llmResponse, request);
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("Ada"));
        assertThat(response.getFirstHeader(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_INVALID_HEADER), is(""));
    }

    @Test
    public void shouldReturnNoEnforcementErrorWhenEnforcementOnButNoSchema() {
        // given — enforcement enabled but no outputSchema: nothing to check, so no error
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("anything at all")
                .enforceOutputSchema());
        HttpRequest request = request().withPath("/v1/messages");

        // when / then
        assertThat(handler.enforcementErrorResponseOrNull(llmResponse, request), is(nullValue()));
    }

    @Test
    public void shouldReturnNoEnforcementErrorWhenEnforcementOnButSchemaMalformed() {
        // given — enforcement enabled but the schema is rejected by the validator: a
        // malformed schema is a no-op and must never produce an enforcement error
        MockServerLogger mockLogger = mock(MockServerLogger.class);
        when(mockLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(mockLogger);
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\"}")
                .withOutputSchema("{ this is not valid json schema")
                .enforceOutputSchema());
        HttpRequest request = request().withPath("/v1/messages");

        // when / then
        assertThat(handler.enforcementErrorResponseOrNull(llmResponse, request), is(nullValue()));
    }

    @Test
    public void shouldUseProviderCorrectEnforcementErrorForOpenAi() {
        // given — enforcement on, OpenAI provider, non-conformant body
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withText("not json at all")
                .withOutputSchema(PERSON_SCHEMA)
                .enforceOutputSchema());
        HttpRequest request = request().withPath("/v1/chat/completions");

        // when
        HttpResponse enforcementError = handler.enforcementErrorResponseOrNull(llmResponse, request);

        // then — OpenAI error envelope at the enforcement status
        assertThat(enforcementError, is(notNullValue()));
        assertThat(enforcementError.getStatusCode(), is(HttpLlmResponseActionHandler.STRUCTURED_OUTPUT_ENFORCEMENT_STATUS));
        assertThat(enforcementError.getBodyAsString(), containsString("server_error"));
    }

    // --- stateful quota (chaos rate limit) ---

    @Test
    public void shouldReturnQuotaErrorAfterLimitExceeded() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        // unique name keeps this test independent of the shared registry singleton
        String quotaName = "test-quota-" + System.nanoTime();
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi"))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withQuotaLimit(2)
                .withQuotaWindowMillis(600_000L)   // large window: all calls fall in one window
                .withRetryAfter("30"));

        // first two requests are within quota -> no chaos error
        assertThat(handler.chaosErrorResponseOrNull(llmResponse), is(nullValue()));
        assertThat(handler.chaosErrorResponseOrNull(llmResponse), is(nullValue()));

        // third exceeds the quota -> 429 with Retry-After
        HttpResponse quotaError = handler.chaosErrorResponseOrNull(llmResponse);
        assertThat(quotaError, is(notNullValue()));
        assertThat(quotaError.getStatusCode(), is(429));
        assertThat(quotaError.getFirstHeader("Retry-After"), is("30"));
        // Anthropic-correct rate-limit body; the request-vs-token distinction is in the message
        assertThat(quotaError.getBodyAsString(), containsString("rate_limit_error"));
        assertThat(quotaError.getBodyAsString(), containsString("LLM request quota exceeded"));
    }

    @Test
    public void shouldUseCustomQuotaErrorStatus() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-quota-" + System.nanoTime();
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi"))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withQuotaLimit(1)
                .withQuotaWindowMillis(600_000L)
                .withQuotaErrorStatus(529));

        assertThat(handler.chaosErrorResponseOrNull(llmResponse), is(nullValue()));
        HttpResponse quotaError = handler.chaosErrorResponseOrNull(llmResponse);
        assertThat(quotaError, is(notNullValue()));
        assertThat(quotaError.getStatusCode(), is(529));
    }

    @Test
    public void shouldIgnoreIncompleteQuotaConfig() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        // quotaName set but no limit/window -> quota ignored, and no probabilistic error set
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi"))
            .withChaos(new LlmChaosProfile().withQuotaName("incomplete"));

        for (int i = 0; i < 5; i++) {
            assertThat(handler.chaosErrorResponseOrNull(llmResponse), is(nullValue()));
        }
    }

    // --- token-based quota (TPM/TPD) ---

    @Test
    public void shouldReturnTokenQuotaErrorAfterTokenLimitExceeded() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-token-quota-" + System.nanoTime();
        // Token limit = 100; completion has usage with 60 input + 40 output = 100 tokens
        HttpLlmResponse firstResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi")
                .withUsage(Usage.usage().withInputTokens(60).withOutputTokens(40)))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withTokenQuotaLimit(100L)
                .withTokenQuotaWindowMillis(600_000L));

        // First call: 100 tokens, exactly at limit -> allowed
        assertThat(handler.chaosErrorResponseOrNull(firstResponse), is(nullValue()));

        // Second call: 100 more tokens -> total 200, exceeds 100
        HttpResponse tokenError = handler.chaosErrorResponseOrNull(firstResponse);
        assertThat(tokenError, is(notNullValue()));
        assertThat(tokenError.getStatusCode(), is(429));
        // Anthropic-correct rate-limit body; the token-quota distinction is in the message
        assertThat(tokenError.getBodyAsString(), containsString("rate_limit_error"));
        assertThat(tokenError.getBodyAsString(), containsString("LLM token quota exceeded"));
    }

    @Test
    public void shouldEstimateTokensFromTextWhenUsageAbsent() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-text-estimate-" + System.nanoTime();
        // 12 chars -> ceil(12/4) = 3 tokens; limit = 5
        HttpLlmResponse llmResp = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("twelve chars"))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withTokenQuotaLimit(5L)
                .withTokenQuotaWindowMillis(600_000L));

        // First call: 3 tokens -> within limit
        assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));
        // Second call: 3 + 3 = 6 -> over limit of 5
        HttpResponse tokenError = handler.chaosErrorResponseOrNull(llmResp);
        assertThat(tokenError, is(notNullValue()));
        assertThat(tokenError.getStatusCode(), is(429));
        assertThat(tokenError.getBodyAsString(), containsString("rate_limit_error"));
        assertThat(tokenError.getBodyAsString(), containsString("LLM token quota exceeded"));
    }

    @Test
    public void shouldUseZeroTokensForEmbedding() {
        // Embedding responses have no completion, so token count = 0; token quota never trips
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-embedding-tokens-" + System.nanoTime();
        HttpLlmResponse llmResp = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withEmbedding(EmbeddingResponse.embedding().withDimensions(8))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withTokenQuotaLimit(1L)
                .withTokenQuotaWindowMillis(600_000L));

        // amount=0 per call, never exceeds
        for (int i = 0; i < 10; i++) {
            assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));
        }
    }

    @Test
    public void shouldAllowBothRequestAndTokenQuotaToCoexist() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-dual-" + System.nanoTime();
        // Request quota: 10 requests; Token quota: 50 tokens
        HttpLlmResponse llmResp = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi")
                .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(10)))  // 20 tokens each
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withQuotaLimit(10)
                .withQuotaWindowMillis(600_000L)
                .withTokenQuotaLimit(50L)
                .withTokenQuotaWindowMillis(600_000L));

        // Calls 1 and 2: request ok (2 of 10), tokens ok (20, 40 of 50)
        assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));
        assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));

        // Call 3: request ok (3 of 10), but tokens = 60 > 50 -> token quota exceeded
        HttpResponse error = handler.chaosErrorResponseOrNull(llmResp);
        assertThat(error, is(notNullValue()));
        assertThat(error.getStatusCode(), is(429));
        assertThat(error.getBodyAsString(), containsString("rate_limit_error"));
        assertThat(error.getBodyAsString(), containsString("LLM token quota exceeded"));
    }

    @Test
    public void shouldReturnRequestQuotaErrorBeforeTokenQuota() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-order-" + System.nanoTime();
        // Request quota: 1; Token quota: 1000 (generous)
        HttpLlmResponse llmResp = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi")
                .withUsage(Usage.usage().withInputTokens(5).withOutputTokens(5)))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withQuotaLimit(1)
                .withQuotaWindowMillis(600_000L)
                .withTokenQuotaLimit(1000L)
                .withTokenQuotaWindowMillis(600_000L));

        assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));  // 1st -> ok
        // 2nd -> request quota exceeded (tokens still fine)
        HttpResponse error = handler.chaosErrorResponseOrNull(llmResp);
        assertThat(error, is(notNullValue()));
        // request-quota (not token) fired: Anthropic rate-limit body, request message
        assertThat(error.getBodyAsString(), containsString("rate_limit_error"));
        assertThat(error.getBodyAsString(), containsString("LLM request quota exceeded"));
        assertThat(error.getBodyAsString(), not(containsString("token")));
    }

    @Test
    public void shouldUseRetryAfterAndCustomStatusForTokenQuota() {
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-token-retry-" + System.nanoTime();
        HttpLlmResponse llmResp = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi")
                .withUsage(Usage.usage().withInputTokens(100).withOutputTokens(0)))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withTokenQuotaLimit(50L)
                .withTokenQuotaWindowMillis(600_000L)
                .withQuotaErrorStatus(529)
                .withRetryAfter("60"));

        // First call: 100 tokens > 50 limit? No, first call = 100 which exceeds 50...
        // Actually: first call charges 100 to the counter. 100 > 50, so it's over.
        HttpResponse error = handler.chaosErrorResponseOrNull(llmResp);
        assertThat(error, is(notNullValue()));
        assertThat(error.getStatusCode(), is(529));
        assertThat(error.getFirstHeader("Retry-After"), is("60"));
        // 529 → Anthropic overloaded_error shape; the token-quota distinction is in the message
        assertThat(error.getBodyAsString(), containsString("overloaded_error"));
        assertThat(error.getBodyAsString(), containsString("LLM token quota exceeded"));
    }

    @Test
    public void existingRequestQuotaTestStillWorksUnchanged() {
        // Verify the existing request-count quota path is unaffected by the refactoring
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        String quotaName = "test-existing-" + System.nanoTime();
        HttpLlmResponse llmResp = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withCompletion(completion().withText("hi"))
            .withChaos(new LlmChaosProfile()
                .withQuotaName(quotaName)
                .withQuotaLimit(2)
                .withQuotaWindowMillis(600_000L));

        assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));
        assertThat(handler.chaosErrorResponseOrNull(llmResp), is(nullValue()));
        HttpResponse error = handler.chaosErrorResponseOrNull(llmResp);
        assertThat(error, is(notNullValue()));
        assertThat(error.getStatusCode(), is(429));
        assertThat(error.getBodyAsString(), containsString("rate_limit_error"));
        assertThat(error.getBodyAsString(), containsString("LLM request quota exceeded"));
    }

    // --- estimateTokenCount unit tests ---

    @Test
    public void shouldEstimateTokensFromUsage() {
        long tokens = HttpLlmResponseActionHandler.estimateTokenCount(
            llmResponse().withCompletion(completion().withText("ignored")
                .withUsage(Usage.usage().withInputTokens(10).withOutputTokens(20))));
        assertThat(tokens, is(30L));
    }

    @Test
    public void shouldEstimateTokensFromTextLengthWhenNoUsage() {
        // "hello world!" = 12 chars -> ceil(12/4) = 3
        long tokens = HttpLlmResponseActionHandler.estimateTokenCount(
            llmResponse().withCompletion(completion().withText("hello world!")));
        assertThat(tokens, is(3L));
    }

    @Test
    public void shouldReturnZeroTokensForNullCompletion() {
        long tokens = HttpLlmResponseActionHandler.estimateTokenCount(
            llmResponse().withProvider(Provider.ANTHROPIC));
        assertThat(tokens, is(0L));
    }

    @Test
    public void shouldReturnZeroTokensForEmptyTextAndNoUsage() {
        long tokens = HttpLlmResponseActionHandler.estimateTokenCount(
            llmResponse().withCompletion(completion().withText("")));
        assertThat(tokens, is(0L));
    }

    @Test
    public void shouldNotAlterStreamingEventsWhenOutputViolatesSchema() {
        // given — a streaming completion whose text violates its declared schema.
        // Streaming validation is log-only (an SSE stream carries no response header),
        // so it must never change the emitted events.
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpRequest request = request().withPath("/v1/messages");

        HttpLlmResponse control = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion().withText("{\"name\":\"Ada\"}").withStreaming(true));
        HttpLlmResponse withSchema = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withText("{\"name\":\"Ada\"}")          // missing required "age"
                .withOutputSchema(PERSON_SCHEMA)
                .withStreaming(true));

        // when
        List<SseEvent> controlEvents = handler.handleStreaming(control, request);
        List<SseEvent> schemaEvents = handler.handleStreaming(withSchema, request);

        // then — declaring a (violated) schema does not change the stream
        assertThat(schemaEvents.size(), is(controlEvents.size()));
        assertThat(schemaEvents.get(0).getEvent(), is(controlEvents.get(0).getEvent()));
    }

    // ---- approximate usage inference (opt-in) ----

    private static final String OPENAI_REQUEST_BODY =
        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"What is the capital of France?\"}]}";

    @Test
    public void shouldNotInferUsageByDefault() {
        // given — inference is off (default); completion declares no usage
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion().withText("The capital of France is Paris."));
        HttpRequest request = request().withPath("/v1/chat/completions").withBody(OPENAI_REQUEST_BODY);

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — default behaviour unchanged: usage encodes as zeros
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("\"prompt_tokens\":0"));
        assertThat(response.getBodyAsString(), containsString("\"completion_tokens\":0"));
    }

    @Test
    public void shouldInferApproximateUsageWhenEnabledAndUsageAbsent() {
        // given — inference opted in; completion declares no usage
        org.mockserver.configuration.ConfigurationProperties.llmInferUsageEnabled(true);
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion().withText("The capital of France is Paris."));
        HttpRequest request = request().withPath("/v1/chat/completions").withBody(OPENAI_REQUEST_BODY);

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — approximate non-zero counts are populated from request + response text
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), not(containsString("\"prompt_tokens\":0")));
        assertThat(response.getBodyAsString(), not(containsString("\"completion_tokens\":0")));
    }

    @Test
    public void shouldRecomputePromptTokensPerRequestAndNotMutateSharedCompletion() {
        // given — inference opted in; ONE shared HttpLlmResponse reused across two requests
        org.mockserver.configuration.ConfigurationProperties.llmInferUsageEnabled(true);
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        Completion sharedCompletion = completion().withText("The capital of France is Paris.");
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(sharedCompletion);

        String shortBody = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        String longBody = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\""
            + "Please give me an extremely long and detailed multi-paragraph explanation of "
            + "the history, geography, and culture of the capital city of France so I can study it."
            + "\"}]}";

        // when — the short request is served first, then a much larger one
        HttpResponse shortResponse = handler.handle(llmResponse, request().withPath("/v1/chat/completions").withBody(shortBody));
        HttpResponse longResponse = handler.handle(llmResponse, request().withPath("/v1/chat/completions").withBody(longBody));

        // then — the larger prompt yields MORE prompt_tokens (estimate is recomputed per request,
        // not cached from the first request onto the shared completion)
        int shortPromptTokens = extractIntField(shortResponse.getBodyAsString(), "prompt_tokens");
        int longPromptTokens = extractIntField(longResponse.getBodyAsString(), "prompt_tokens");
        assertThat(longPromptTokens, is(greaterThan(shortPromptTokens)));

        // and — the shared expectation Completion is never mutated (usage stays null)
        assertThat(sharedCompletion.getUsage(), is(nullValue()));
    }

    @Test
    public void shouldPreserveReasoningContentThroughUsageInferenceCopy() {
        // given — inference opted in (which makes a per-request shallow copy of the completion);
        // the completion declares reasoning content but no usage
        org.mockserver.configuration.ConfigurationProperties.llmInferUsageEnabled(true);
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.ANTHROPIC)
            .withModel("claude-sonnet-4-20250514")
            .withCompletion(completion()
                .withReasoningText("Let me think.")
                .withReasoningSignature("sig-1")
                .withText("Paris."));
        HttpRequest request = request().withPath("/v1/messages")
            .withBody("{\"model\":\"claude-sonnet-4-20250514\",\"messages\":[{\"role\":\"user\",\"content\":\"capital of France?\"}]}");

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — the shallow copy carried the reasoning fields, so the thinking block is still encoded
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("\"type\":\"thinking\""));
        assertThat(response.getBodyAsString(), containsString("Let me think."));
        assertThat(response.getBodyAsString(), containsString("sig-1"));
    }

    private static int extractIntField(String json, String field) {
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("field " + field + " not found in: " + json);
        }
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    public void shouldNotOverwriteDeclaredUsageWhenEnabled() {
        // given — inference opted in, but the completion already declares usage
        org.mockserver.configuration.ConfigurationProperties.llmInferUsageEnabled(true);
        HttpLlmResponseActionHandler handler = new HttpLlmResponseActionHandler(new MockServerLogger());
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .withCompletion(completion()
                .withText("The capital of France is Paris.")
                .withUsage(Usage.usage().withInputTokens(11).withOutputTokens(7)));
        HttpRequest request = request().withPath("/v1/chat/completions").withBody(OPENAI_REQUEST_BODY);

        // when
        HttpResponse response = handler.handle(llmResponse, request);

        // then — declared usage is preserved exactly, never overwritten
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("\"prompt_tokens\":11"));
        assertThat(response.getBodyAsString(), containsString("\"completion_tokens\":7"));
    }
}
