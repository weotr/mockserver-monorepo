package org.mockserver.llm.codec;

import org.mockserver.llm.ProviderCodec;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.RerankResponse;

import java.util.List;

/**
 * Codec for Voyage AI's rerank endpoint ({@code POST /v1/rerank}). Voyage is a
 * rerank-only provider in MockServer — it exposes only
 * {@link #encodeRerank(RerankResponse, List)} and does not participate in the
 * chat/completion encode/decode paths. The Voyage response shape is the
 * OpenAI-style list envelope
 * {@code {"object":"list","data":[{"index":N,"relevance_score":F}, ...],"usage":{"total_tokens":N}}},
 * sorted by descending relevance.
 */
public class VoyageCodec implements ProviderCodec {

    @Override
    public Provider provider() {
        return Provider.VOYAGE;
    }

    @Override
    public String apiVersion() {
        return "voyage-rerank-2";
    }

    @Override
    public HttpResponse encodeRerank(RerankResponse rerank, List<String> documents) {
        return RerankScoring.encode(rerank, documents, "rerank-2", RerankScoring.Envelope.VOYAGE_DATA);
    }
}
