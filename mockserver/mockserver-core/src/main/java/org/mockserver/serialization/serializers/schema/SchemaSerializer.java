package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.Schema;


/**
 * @author jamesdbloom
 */
@SuppressWarnings("rawtypes")
public class SchemaSerializer extends AbstractSchemaSerializer<Schema> {
    private static final long serialVersionUID = 1L;

    public SchemaSerializer() {
        super(Schema.class);
    }

}

