package org.mockserver.serialization;

import com.fasterxml.jackson.databind.JsonSerializer;
import org.mockserver.serialization.serializers.matcher.HttpRequestsPropertiesMatcherSerializer;
import org.mockserver.serialization.serializers.schema.ArraySchemaSerializer;
import org.mockserver.serialization.serializers.schema.BinarySchemaSerializer;
import org.mockserver.serialization.serializers.schema.BooleanSchemaSerializer;
import org.mockserver.serialization.serializers.schema.ByteArraySchemaSerializer;
import org.mockserver.serialization.serializers.schema.ComposedSchemaSerializer;
import org.mockserver.serialization.serializers.schema.DateSchemaSerializer;
import org.mockserver.serialization.serializers.schema.DateTimeSchemaSerializer;
import org.mockserver.serialization.serializers.schema.EmailSchemaSerializer;
import org.mockserver.serialization.serializers.schema.FileSchemaSerializer;
import org.mockserver.serialization.serializers.schema.IntegerSchemaSerializer;
import org.mockserver.serialization.serializers.schema.MapSchemaSerializer;
import org.mockserver.serialization.serializers.schema.NumberSchemaSerializer;
import org.mockserver.serialization.serializers.schema.ObjectSchemaSerializer;
import org.mockserver.serialization.serializers.schema.PasswordSchemaSerializer;
import org.mockserver.serialization.serializers.schema.SchemaSerializer;
import org.mockserver.serialization.serializers.schema.StringSchemaSerializer;
import org.mockserver.serialization.serializers.schema.UUIDSchemaSerializer;

import java.util.Arrays;
import java.util.List;

/**
 * Holds the JSON serializers that reference Swagger/OpenAPI model types
 * (<code>io.swagger.v3.oas.models.*</code>): the schema serializers and the
 * {@link HttpRequestsPropertiesMatcherSerializer} (which handles the OpenAPI-derived
 * {@link org.mockserver.matchers.HttpRequestsPropertiesMatcher}).
 *
 * <p>These objects only ever need serialising on the server side, after MockServer has
 * parsed an OpenAPI specification; a remote client never produces them. Keeping every
 * Swagger reference inside this single class means {@link ObjectMapperFactory} touches
 * Swagger classes only when it deliberately calls {@link #swaggerSerializers()} after
 * confirming swagger-core is on the classpath (see {@code ObjectMapperFactory.SWAGGER_PRESENT}).
 * That lets {@code mockserver-client-java} exclude the Swagger/OpenAPI parser entirely
 * without the object mapper failing to initialise with a {@code NoClassDefFoundError}.</p>
 *
 * @author jamesdbloom
 */
@SuppressWarnings({"rawtypes"})
class SwaggerSerializers {

    /**
     * @return the Swagger/OpenAPI-coupled serializers; only call when swagger-core is present.
     */
    static List<JsonSerializer> swaggerSerializers() {
        return Arrays.asList(
            // OpenAPI-derived matcher
            new HttpRequestsPropertiesMatcherSerializer(),
            // schema
            new SchemaSerializer(),
            new ArraySchemaSerializer(),
            new BinarySchemaSerializer(),
            new BooleanSchemaSerializer(),
            new ByteArraySchemaSerializer(),
            new ComposedSchemaSerializer(),
            new DateSchemaSerializer(),
            new DateTimeSchemaSerializer(),
            new EmailSchemaSerializer(),
            new FileSchemaSerializer(),
            new IntegerSchemaSerializer(),
            new MapSchemaSerializer(),
            new NumberSchemaSerializer(),
            new ObjectSchemaSerializer(),
            new PasswordSchemaSerializer(),
            new StringSchemaSerializer(),
            new UUIDSchemaSerializer()
        );
    }

}
