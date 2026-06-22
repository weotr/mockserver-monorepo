package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.ArraySchema;


/**
 * @author jamesdbloom
 */
public class ArraySchemaSerializer extends AbstractSchemaSerializer<ArraySchema> {
    private static final long serialVersionUID = 1L;

    public ArraySchemaSerializer() {
        super(ArraySchema.class);
    }

}
