package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.serialization.model.FuzzyBodyDTO;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class FuzzyBodyDTOSerializer extends StdSerializer<FuzzyBodyDTO> {

    private static final long serialVersionUID = 1L;

    public FuzzyBodyDTOSerializer() {
        super(FuzzyBodyDTO.class);
    }

    @Override
    public void serialize(FuzzyBodyDTO fuzzyBodyDTO, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (fuzzyBodyDTO.getNot() != null && fuzzyBodyDTO.getNot()) {
            jgen.writeBooleanField("not", fuzzyBodyDTO.getNot());
        }
        if (fuzzyBodyDTO.getOptional() != null && fuzzyBodyDTO.getOptional()) {
            jgen.writeBooleanField("optional", fuzzyBodyDTO.getOptional());
        }
        jgen.writeStringField("type", fuzzyBodyDTO.getType().name());
        jgen.writeStringField("fuzzy", fuzzyBodyDTO.getFuzzy());
        jgen.writeNumberField("threshold", fuzzyBodyDTO.getThreshold());
        if (fuzzyBodyDTO.isIgnoreCase()) {
            jgen.writeBooleanField("ignoreCase", true);
        }
        jgen.writeEndObject();
    }
}
