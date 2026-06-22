package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.serialization.model.MultipartBodyDTO;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class MultipartBodyDTOSerializer extends StdSerializer<MultipartBodyDTO> {

    private static final long serialVersionUID = 1L;

    public MultipartBodyDTOSerializer() {
        super(MultipartBodyDTO.class);
    }

    @Override
    public void serialize(MultipartBodyDTO multipartBodyDTO, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (multipartBodyDTO.getNot() != null && multipartBodyDTO.getNot()) {
            jgen.writeBooleanField("not", multipartBodyDTO.getNot());
        }
        if (multipartBodyDTO.getOptional() != null && multipartBodyDTO.getOptional()) {
            jgen.writeBooleanField("optional", multipartBodyDTO.getOptional());
        }
        jgen.writeStringField("type", multipartBodyDTO.getType().name());
        if (multipartBodyDTO.getFields() != null && !multipartBodyDTO.getFields().isEmpty()) {
            jgen.writeObjectField("fields", multipartBodyDTO.getFields());
        }
        if (multipartBodyDTO.getFilenames() != null && !multipartBodyDTO.getFilenames().isEmpty()) {
            jgen.writeObjectField("filenames", multipartBodyDTO.getFilenames());
        }
        if (multipartBodyDTO.getPartContentTypes() != null && !multipartBodyDTO.getPartContentTypes().isEmpty()) {
            jgen.writeObjectField("partContentTypes", multipartBodyDTO.getPartContentTypes());
        }
        jgen.writeEndObject();
    }
}
