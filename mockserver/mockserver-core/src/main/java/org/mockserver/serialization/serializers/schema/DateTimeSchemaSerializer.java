package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.DateTimeSchema;


/**
 * @author jamesdbloom
 */
public class DateTimeSchemaSerializer extends AbstractSchemaSerializer<DateTimeSchema> {
    private static final long serialVersionUID = 1L;

    public DateTimeSchemaSerializer() {
        super(DateTimeSchema.class);
    }

}

