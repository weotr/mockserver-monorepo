package org.mockserver.llm.codec;

import org.mockserver.llm.ProviderCodec;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.RerankResponse;

import java.util.List;

/**
 * Codec for Cohere's rerank endpoint ({@code POST /v1/rerank}). Cohere is a
 * rerank-only provider in MockServer — it exposes only
 * {@link #encodeRerank(RerankResponse, List)} and does not participate in the
 * chat/completion encode/decode paths. The response shape is
 * {@code {"results":[{"index":N,"relevance_score":F}, ...]}}, sorted by
 * descending relevance.
 */
public class CohereCodec implements ProviderCodec {

    @Override
    public Provider provider() {
        return Provider.COHERE;
    }

    @Override
    public String apiVersion() {
        return "cohere-rerank-v3";
    }

    @Override
    public HttpResponse encodeRerank(RerankResponse rerank, List<String> documents) {
        return RerankScoring.encode(rerank, documents, "rerank-english-v3.0", RerankScoring.Envelope.COHERE_RESULTS);
    }
}
