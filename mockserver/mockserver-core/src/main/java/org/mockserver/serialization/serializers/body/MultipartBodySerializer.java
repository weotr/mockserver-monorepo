package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.MultipartBody;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class MultipartBodySerializer extends StdSerializer<MultipartBody> {

    private static final long serialVersionUID = 1L;

    public MultipartBodySerializer() {
        super(MultipartBody.class);
    }

    @Override
    public void serialize(MultipartBody multipartBody, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (multipartBody.getNot() != null && multipartBody.getNot()) {
            jgen.writeBooleanField("not", multipartBody.getNot());
        }
        if (multipartBody.getOptional() != null && multipartBody.getOptional()) {
            jgen.writeBooleanField("optional", multipartBody.getOptional());
        }
        jgen.writeStringField("type", multipartBody.getType().name());
        if (multipartBody.getFields() != null && !multipartBody.getFields().isEmpty()) {
            jgen.writeObjectField("fields", multipartBody.getFields());
        }
        if (multipartBody.getFilenames() != null && !multipartBody.getFilenames().isEmpty()) {
            jgen.writeObjectField("filenames", multipartBody.getFilenames());
        }
        if (multipartBody.getPartContentTypes() != null && !multipartBody.getPartContentTypes().isEmpty()) {
            jgen.writeObjectField("partContentTypes", multipartBody.getPartContentTypes());
        }
        jgen.writeEndObject();
    }
}
