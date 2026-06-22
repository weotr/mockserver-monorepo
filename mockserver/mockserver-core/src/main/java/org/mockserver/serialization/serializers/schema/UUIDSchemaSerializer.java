package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.UUIDSchema;


/**
 * @author jamesdbloom
 */
public class UUIDSchemaSerializer extends AbstractSchemaSerializer<UUIDSchema> {
    private static final long serialVersionUID = 1L;

    public UUIDSchemaSerializer() {
        super(UUIDSchema.class);
    }

}