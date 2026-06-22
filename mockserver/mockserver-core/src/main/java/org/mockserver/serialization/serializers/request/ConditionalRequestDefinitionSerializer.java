package org.mockserver.serialization.serializers.request;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.ConditionalRequestDefinition;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class ConditionalRequestDefinitionSerializer extends StdSerializer<ConditionalRequestDefinition> {
    private static final long serialVersionUID = 1L;

    public ConditionalRequestDefinitionSerializer() {
        super(ConditionalRequestDefinition.class);
    }

    @Override
    public void serialize(ConditionalRequestDefinition conditionalRequestDefinition, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (conditionalRequestDefinition.getNot() != null && conditionalRequestDefinition.getNot()) {
            jgen.writeBooleanField("not", conditionalRequestDefinition.getNot());
        }
        if (conditionalRequestDefinition.getIf() != null) {
            jgen.writeObjectField("if", conditionalRequestDefinition.getIf());
        }
        if (conditionalRequestDefinition.getThen() != null) {
            jgen.writeObjectField("then", conditionalRequestDefinition.getThen());
        }
        if (conditionalRequestDefinition.getElse() != null) {
            jgen.writeObjectField("else", conditionalRequestDefinition.getElse());
        }
        jgen.writeEndObject();
    }
}
