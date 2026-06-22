package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.DateSchema;


/**
 * @author jamesdbloom
 */
public class DateSchemaSerializer extends AbstractSchemaSerializer<DateSchema> {
    private static final long serialVersionUID = 1L;

    public DateSchemaSerializer() {
        super(DateSchema.class);
    }

}

