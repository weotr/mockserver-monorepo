package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.NumberSchema;


/**
 * @author jamesdbloom
 */
public class NumberSchemaSerializer extends AbstractSchemaSerializer<NumberSchema> {
    private static final long serialVersionUID = 1L;

    public NumberSchemaSerializer() {
        super(NumberSchema.class);
    }

}
