package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.EmailSchema;


/**
 * @author jamesdbloom
 */
public class EmailSchemaSerializer extends AbstractSchemaSerializer<EmailSchema> {
    private static final long serialVersionUID = 1L;

    public EmailSchemaSerializer() {
        super(EmailSchema.class);
    }

}
