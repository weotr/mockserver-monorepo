package org.mockserver.serialization.model;

import org.mockserver.model.Delay;
import org.mockserver.model.DelayDistribution;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.concurrent.TimeUnit;

/**
 * @author jamesdbloom
 */
public class DelayDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<Delay> {

    private TimeUnit timeUnit;
    private long value;
    private DelayDistributionDTO distribution;
    private String template;
    private HttpTemplate.TemplateType templateType;

    public DelayDTO(Delay delay) {
        if (delay != null) {
            timeUnit = delay.getTimeUnit();
            value = delay.getValue();
            if (delay.getDistribution() != null) {
                distribution = new DelayDistributionDTO(delay.getDistribution());
            }
            template = delay.getTemplate();
            templateType = delay.getTemplateType();
        }
    }

    public DelayDTO() {
    }

    public Delay buildObject() {
        DelayDistribution builtDistribution = distribution != null ? distribution.buildObject() : null;
        if (template != null && templateType != null) {
            return new Delay(timeUnit, value, builtDistribution, template, templateType);
        }
        if (builtDistribution != null) {
            return new Delay(timeUnit, value, builtDistribution);
        }
        return new Delay(timeUnit, value);
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public DelayDTO setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
        return this;
    }

    public long getValue() {
        return value;
    }

    public DelayDTO setValue(long value) {
        this.value = value;
        return this;
    }

    public DelayDistributionDTO getDistribution() {
        return distribution;
    }

    public DelayDTO setDistribution(DelayDistributionDTO distribution) {
        this.distribution = distribution;
        return this;
    }

    public String getTemplate() {
        return template;
    }

    public DelayDTO setTemplate(String template) {
        this.template = template;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public DelayDTO setTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }
}
