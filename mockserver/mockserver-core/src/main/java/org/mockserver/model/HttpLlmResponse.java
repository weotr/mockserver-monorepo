package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.matchers.LlmConversationMatcher;

import java.util.Objects;

public class HttpLlmResponse extends Action<HttpLlmResponse> {
    private int hashCode;
    private Provider provider;
    private String model;
    private Completion completion;
    private EmbeddingResponse embedding;
    private RerankResponse rerank;
    private ConversationPredicates conversationPredicates;
    private LlmChaosProfile chaos;
    // volatile: lazily reconstructed (see getConversationMatcher) on the concurrent
    // match path from a shared expectation object. The value is effectively immutable
    // once set, so volatile guarantees safe publication under the Java Memory Model — a
    // thread that observes a non-null reference also observes a fully-constructed matcher.
    @JsonIgnore
    private transient volatile LlmConversationMatcher conversationMatcher;

    public static HttpLlmResponse llmResponse() {
        return new HttpLlmResponse();
    }

    public HttpLlmResponse withProvider(Provider provider) {
        this.provider = provider;
        this.hashCode = 0;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    public HttpLlmResponse withModel(String model) {
        this.model = model;
        this.hashCode = 0;
        return this;
    }

    public String getModel() {
        return model;
    }

    public HttpLlmResponse withCompletion(Completion completion) {
        this.completion = completion;
        this.hashCode = 0;
        return this;
    }

    public Completion getCompletion() {
        return completion;
    }

    public HttpLlmResponse withEmbedding(EmbeddingResponse embedding) {
        this.embedding = embedding;
        this.hashCode = 0;
        return this;
    }

    public EmbeddingResponse getEmbedding() {
        return embedding;
    }

    public HttpLlmResponse withRerank(RerankResponse rerank) {
        this.rerank = rerank;
        this.hashCode = 0;
        return this;
    }

    public RerankResponse getRerank() {
        return rerank;
    }

    public HttpLlmResponse withConversationPredicates(ConversationPredicates conversationPredicates) {
        this.conversationPredicates = conversationPredicates;
        this.hashCode = 0;
        this.conversationMatcher = null; // invalidate cached matcher
        return this;
    }

    public ConversationPredicates getConversationPredicates() {
        return conversationPredicates;
    }

    /**
     * Optional fault/chaos profile applied when returning this response —
     * probabilistic errors, mid-stream truncation, malformed SSE.
     */
    public HttpLlmResponse withChaos(LlmChaosProfile chaos) {
        this.chaos = chaos;
        this.hashCode = 0;
        return this;
    }

    public LlmChaosProfile getChaos() {
        return chaos;
    }

    public HttpLlmResponse withConversationMatcher(LlmConversationMatcher conversationMatcher) {
        this.conversationMatcher = conversationMatcher;
        return this;
    }

    /**
     * Returns the conversation matcher for evaluation. If not explicitly set,
     * lazily reconstructs it from {@link #conversationPredicates} (which survives
     * JSON round-tripping). Returns null if neither is set.
     */
    @JsonIgnore
    public LlmConversationMatcher getConversationMatcher() {
        if (conversationMatcher == null && conversationPredicates != null && conversationPredicates.hasAnyPredicate()) {
            LlmConversationMatcher matcher = new LlmConversationMatcher();
            matcher.withProvider(provider);
            if (conversationPredicates.getTurnIndex() != null) {
                matcher.withTurnIndex(conversationPredicates.getTurnIndex());
            }
            if (conversationPredicates.getLatestMessageContains() != null) {
                matcher.withLatestMessageContains(conversationPredicates.getLatestMessageContains());
            }
            if (conversationPredicates.getLatestMessageMatches() != null) {
                matcher.withLatestMessageMatches(conversationPredicates.getLatestMessageMatches());
            }
            if (conversationPredicates.getLatestMessageRole() != null) {
                matcher.withLatestMessageRole(conversationPredicates.getLatestMessageRole());
            }
            if (conversationPredicates.getContainsToolResultFor() != null) {
                matcher.withContainsToolResultFor(conversationPredicates.getContainsToolResultFor());
            }
            if (conversationPredicates.getSemanticMatchAgainst() != null) {
                matcher.withSemanticMatchAgainst(conversationPredicates.getSemanticMatchAgainst());
            }
            if (conversationPredicates.getNormalization() != null) {
                matcher.withNormalization(conversationPredicates.getNormalization());
            }
            conversationMatcher = matcher;
        }
        return conversationMatcher;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.LLM_RESPONSE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        HttpLlmResponse that = (HttpLlmResponse) o;
        return Objects.equals(provider, that.provider) &&
            Objects.equals(model, that.model) &&
            Objects.equals(completion, that.completion) &&
            Objects.equals(embedding, that.embedding) &&
            Objects.equals(rerank, that.rerank) &&
            Objects.equals(conversationPredicates, that.conversationPredicates) &&
            Objects.equals(chaos, that.chaos);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), provider, model, completion, embedding, rerank, conversationPredicates, chaos);
        }
        return hashCode;
    }
}
