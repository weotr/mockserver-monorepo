package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.BinarySchema;


/**
 * @author jamesdbloom
 */
public class BinarySchemaSerializer extends AbstractSchemaSerializer<BinarySchema> {
    private static final long serialVersionUID = 1L;

    public BinarySchemaSerializer() {
        super(BinarySchema.class);
    }

}
