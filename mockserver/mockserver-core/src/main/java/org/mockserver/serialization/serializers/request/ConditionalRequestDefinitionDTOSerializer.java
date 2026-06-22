package org.mockserver.serialization.serializers.request;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.serialization.model.ConditionalRequestDefinitionDTO;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class ConditionalRequestDefinitionDTOSerializer extends StdSerializer<ConditionalRequestDefinitionDTO> {
    private static final long serialVersionUID = 1L;

    public ConditionalRequestDefinitionDTOSerializer() {
        super(ConditionalRequestDefinitionDTO.class);
    }

    @Override
    public void serialize(ConditionalRequestDefinitionDTO conditionalRequestDefinition, JsonGenerator jgen, SerializerProvider provider) throws IOException {
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
