package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.BooleanSchema;


/**
 * @author jamesdbloom
 */
public class BooleanSchemaSerializer extends AbstractSchemaSerializer<BooleanSchema> {
    private static final long serialVersionUID = 1L;

    public BooleanSchemaSerializer() {
        super(BooleanSchema.class);
    }

}