package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.IntegerSchema;


/**
 * @author jamesdbloom
 */
public class IntegerSchemaSerializer extends AbstractSchemaSerializer<IntegerSchema> {
    private static final long serialVersionUID = 1L;

    public IntegerSchemaSerializer() {
        super(IntegerSchema.class);
    }

}