package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.MapSchema;


/**
 * @author jamesdbloom
 */
public class MapSchemaSerializer extends AbstractSchemaSerializer<MapSchema> {
    private static final long serialVersionUID = 1L;

    public MapSchemaSerializer() {
        super(MapSchema.class);
    }

}
