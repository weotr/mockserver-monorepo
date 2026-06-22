package org.mockserver.serialization.model;

import org.mockserver.model.Body;
import org.mockserver.model.FuzzyBody;

/**
 * @author jamesdbloom
 */
public class FuzzyBodyDTO extends BodyDTO {

    private final String fuzzy;
    private final double threshold;
    private final boolean ignoreCase;

    public FuzzyBodyDTO(FuzzyBody fuzzyBody) {
        this(fuzzyBody, null);
    }

    public FuzzyBodyDTO(FuzzyBody fuzzyBody, Boolean not) {
        super(Body.Type.FUZZY, not);
        this.fuzzy = fuzzyBody.getValue();
        this.threshold = fuzzyBody.getThreshold();
        this.ignoreCase = fuzzyBody.isIgnoreCase();
        withOptional(fuzzyBody.getOptional());
    }

    public String getFuzzy() {
        return fuzzy;
    }

    public double getThreshold() {
        return threshold;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public FuzzyBody buildObject() {
        return (FuzzyBody) new FuzzyBody(getFuzzy(), getThreshold(), isIgnoreCase()).withOptional(getOptional());
    }
}
