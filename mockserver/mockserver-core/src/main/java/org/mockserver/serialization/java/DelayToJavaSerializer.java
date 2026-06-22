package org.mockserver.serialization.java;

import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.model.Delay;
import org.mockserver.model.DelayDistribution;

/**
 * @author jamesdbloom
 */
public class DelayToJavaSerializer implements ToJavaSerializer<Delay> {

    @Override
    public String serialize(int numberOfSpacesToIndent, Delay delay) {
        StringBuilder output = new StringBuilder();
        if (delay != null) {
            DelayDistribution distribution = delay.getDistribution();
            if (delay.hasTemplate()) {
                output.append("Delay.template(HttpTemplate.TemplateType.").append(delay.getTemplateType().name())
                    .append(", \"").append(StringEscapeUtils.escapeJava(delay.getTemplate())).append("\")");
            } else if (distribution != null && distribution.getType() != null) {
                switch (distribution.getType()) {
                    case UNIFORM:
                        output.append("Delay.uniform(TimeUnit.").append(delay.getTimeUnit().name())
                            .append(", ").append(distribution.getMin())
                            .append(", ").append(distribution.getMax())
                            .append(")");
                        break;
                    case LOG_NORMAL:
                        output.append("Delay.logNormal(TimeUnit.").append(delay.getTimeUnit().name())
                            .append(", ").append(distribution.getMedian())
                            .append(", ").append(distribution.getP99())
                            .append(")");
                        break;
                    case GAUSSIAN:
                        output.append("Delay.gaussian(TimeUnit.").append(delay.getTimeUnit().name())
                            .append(", ").append(distribution.getMean())
                            .append(", ").append(distribution.getStdDev())
                            .append(")");
                        break;
                    default:
                        output.append("new Delay(TimeUnit.").append(delay.getTimeUnit().name()).append(", ").append(delay.getValue()).append(")");
                        break;
                }
            } else {
                output.append("new Delay(TimeUnit.").append(delay.getTimeUnit().name()).append(", ").append(delay.getValue()).append(")");
            }
        }
        return output.toString();
    }
}
