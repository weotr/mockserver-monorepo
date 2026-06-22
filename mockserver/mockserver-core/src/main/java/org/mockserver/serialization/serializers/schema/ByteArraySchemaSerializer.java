package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.ByteArraySchema;


/**
 * @author jamesdbloom
 */
public class ByteArraySchemaSerializer extends AbstractSchemaSerializer<ByteArraySchema> {
    private static final long serialVersionUID = 1L;

    public ByteArraySchemaSerializer() {
        super(ByteArraySchema.class);
    }

}
