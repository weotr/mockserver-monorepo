package org.mockserver.serialization.serializers.schema;

import io.swagger.v3.oas.models.media.FileSchema;


/**
 * @author jamesdbloom
 */
public class FileSchemaSerializer extends AbstractSchemaSerializer<FileSchema> {
    private static final long serialVersionUID = 1L;

    public FileSchemaSerializer() {
        super(FileSchema.class);
    }

}