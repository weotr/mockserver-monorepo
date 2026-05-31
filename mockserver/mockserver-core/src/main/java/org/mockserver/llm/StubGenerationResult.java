package org.mockserver.llm;

import org.mockserver.mock.Expectation;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.List;

/**
 * Result returned by the AI stub generation endpoint. Contains one or more
 * suggested {@link Expectation}s, a confidence score, and an optional
 * explanation.
 */
public class StubGenerationResult extends ObjectWithReflectiveEqualsHashCodeToString {

    private List<Expectation> suggestions;
    private double confidence;
    private String explanation;
    private String rawLlmResponse;

    public List<Expectation> getSuggestions() {
        return suggestions;
    }

    public StubGenerationResult setSuggestions(List<Expectation> suggestions) {
        this.suggestions = suggestions;
        return this;
    }

    public double getConfidence() {
        return confidence;
    }

    public StubGenerationResult setConfidence(double confidence) {
        this.confidence = confidence;
        return this;
    }

    public String getExplanation() {
        return explanation;
    }

    public StubGenerationResult setExplanation(String explanation) {
        this.explanation = explanation;
        return this;
    }

    public String getRawLlmResponse() {
        return rawLlmResponse;
    }

    public StubGenerationResult setRawLlmResponse(String rawLlmResponse) {
        this.rawLlmResponse = rawLlmResponse;
        return this;
    }
}
