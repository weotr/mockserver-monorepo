package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.StringSchema;


/**
 * @author jamesdbloom
 */
public class StringSchemaSerializer extends AbstractSchemaSerializer<StringSchema> {
    private static final long serialVersionUID = 1L;

    public StringSchemaSerializer() {
        super(StringSchema.class);
    }

}
