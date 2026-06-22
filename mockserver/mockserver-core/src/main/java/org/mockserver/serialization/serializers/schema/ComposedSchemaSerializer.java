package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.ComposedSchema;


/**
 * @author jamesdbloom
 */
public class ComposedSchemaSerializer extends AbstractSchemaSerializer<ComposedSchema> {
    private static final long serialVersionUID = 1L;

    public ComposedSchemaSerializer() {
        super(ComposedSchema.class);
    }

}
