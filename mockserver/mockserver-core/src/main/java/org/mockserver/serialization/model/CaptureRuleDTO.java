package org.mockserver.serialization.model;

import org.mockserver.model.CaptureRule;
import org.mockserver.model.ObjectWithJsonToString;

public class CaptureRuleDTO extends ObjectWithJsonToString implements DTO<CaptureRule> {

    private CaptureRule.Source source;
    private String expression;
    private String into;

    public CaptureRuleDTO() {
    }

    public CaptureRuleDTO(CaptureRule captureRule) {
        if (captureRule != null) {
            this.source = captureRule.getSource();
            this.expression = captureRule.getExpression();
            this.into = captureRule.getInto();
        }
    }

    @Override
    public CaptureRule buildObject() {
        return new CaptureRule()
            .withSource(source)
            .withExpression(expression)
            .withInto(into);
    }

    public CaptureRule.Source getSource() {
        return source;
    }

    public CaptureRuleDTO setSource(CaptureRule.Source source) {
        this.source = source;
        return this;
    }

    public String getExpression() {
        return expression;
    }

    public CaptureRuleDTO setExpression(String expression) {
        this.expression = expression;
        return this;
    }

    public String getInto() {
        return into;
    }

    public CaptureRuleDTO setInto(String into) {
        this.into = into;
        return this;
    }
}
