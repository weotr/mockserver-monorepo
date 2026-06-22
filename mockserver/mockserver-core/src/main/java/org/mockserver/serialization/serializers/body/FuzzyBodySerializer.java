package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.FuzzyBody;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class FuzzyBodySerializer extends StdSerializer<FuzzyBody> {

    private static final long serialVersionUID = 1L;

    public FuzzyBodySerializer() {
        super(FuzzyBody.class);
    }

    @Override
    public void serialize(FuzzyBody fuzzyBody, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (fuzzyBody.getNot() != null && fuzzyBody.getNot()) {
            jgen.writeBooleanField("not", fuzzyBody.getNot());
        }
        if (fuzzyBody.getOptional() != null && fuzzyBody.getOptional()) {
            jgen.writeBooleanField("optional", fuzzyBody.getOptional());
        }
        jgen.writeStringField("type", fuzzyBody.getType().name());
        jgen.writeStringField("fuzzy", fuzzyBody.getValue());
        jgen.writeNumberField("threshold", fuzzyBody.getThreshold());
        if (fuzzyBody.isIgnoreCase()) {
            jgen.writeBooleanField("ignoreCase", true);
        }
        jgen.writeEndObject();
    }
}
