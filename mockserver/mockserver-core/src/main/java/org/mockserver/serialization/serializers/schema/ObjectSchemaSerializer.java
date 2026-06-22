package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.ObjectSchema;


/**
 * @author jamesdbloom
 */
public class ObjectSchemaSerializer extends AbstractSchemaSerializer<ObjectSchema> {
    private static final long serialVersionUID = 1L;

    public ObjectSchemaSerializer() {
        super(ObjectSchema.class);
    }

}
