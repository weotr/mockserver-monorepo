package org.mockserver.model;

import java.util.Objects;

/**
 * Configuration for a mocked rerank response (Cohere {@code /v1/rerank},
 * Voyage {@code /v1/rerank}, ...). A rerank endpoint scores a list of candidate
 * documents against a query and returns them ordered by relevance.
 * <p>
 * MockServer derives one result per candidate document from the request, scoring
 * each with a relevance score (descending) — reproducible when
 * {@code deterministicFromInput} is set, otherwise random. Each result is a
 * {@code {"index":N,"relevance_score":F}} entry; the surrounding envelope is
 * provider-specific (Cohere: top-level {@code results}; Voyage: OpenAI-style
 * {@code data} list with an {@code object}/{@code usage} wrapper).
 */
public class RerankResponse extends ObjectWithJsonToString {
    private int hashCode;
    private Integer topN;
    private Boolean deterministicFromInput;
    private Long seed;

    public static RerankResponse rerank() {
        return new RerankResponse();
    }

    /**
     * Cap the number of returned results to the top N most-relevant documents
     * (mirrors the provider {@code top_n} request parameter). When null, every
     * candidate document is returned.
     */
    public RerankResponse withTopN(Integer topN) {
        this.topN = topN;
        this.hashCode = 0;
        return this;
    }

    public Integer getTopN() {
        return topN;
    }

    public RerankResponse withDeterministicFromInput(Boolean deterministicFromInput) {
        this.deterministicFromInput = deterministicFromInput;
        this.hashCode = 0;
        return this;
    }

    public Boolean getDeterministicFromInput() {
        return deterministicFromInput;
    }

    public RerankResponse withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
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
        RerankResponse that = (RerankResponse) o;
        return Objects.equals(topN, that.topN) &&
            Objects.equals(deterministicFromInput, that.deterministicFromInput) &&
            Objects.equals(seed, that.seed);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(topN, deterministicFromInput, seed);
        }
        return hashCode;
    }
}
