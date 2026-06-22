package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.PasswordSchema;


/**
 * @author jamesdbloom
 */
public class PasswordSchemaSerializer extends AbstractSchemaSerializer<PasswordSchema> {
    private static final long serialVersionUID = 1L;

    public PasswordSchemaSerializer() {
        super(PasswordSchema.class);
    }

}

