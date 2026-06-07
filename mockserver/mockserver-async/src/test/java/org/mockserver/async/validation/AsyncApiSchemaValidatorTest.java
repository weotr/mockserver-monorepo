package org.mockserver.async.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link AsyncApiSchemaValidator}.
 */
public class AsyncApiSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AsyncApiSchemaValidator validator = new AsyncApiSchemaValidator();

    @Test
    public void shouldAcceptValidPayload() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"age\": {\"type\": \"integer\"}}, \"required\": [\"name\"]}"
        );

        AsyncApiSchemaValidator.ValidationResult result = validator.validate(
            "{\"name\": \"Alice\", \"age\": 30}", schema);

        assertThat(result.isValid(), is(true));
    }

    @Test
    public void shouldRejectMissingRequiredField() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}"
        );

        AsyncApiSchemaValidator.ValidationResult result = validator.validate("{}", schema);

        assertThat(result.isValid(), is(false));
        assertThat(result.getErrors(), containsString("name"));
    }

    @Test
    public void shouldRejectWrongType() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"count\": {\"type\": \"integer\"}}}"
        );

        AsyncApiSchemaValidator.ValidationResult result = validator.validate(
            "{\"count\": \"not-a-number\"}", schema);

        assertThat(result.isValid(), is(false));
    }

    @Test
    public void shouldAcceptValidEnum() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"status\": {\"type\": \"string\", \"enum\": [\"active\", \"inactive\"]}}}"
        );

        AsyncApiSchemaValidator.ValidationResult result = validator.validate(
            "{\"status\": \"active\"}", schema);

        assertThat(result.isValid(), is(true));
    }

    @Test
    public void shouldRejectInvalidEnum() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"status\": {\"type\": \"string\", \"enum\": [\"active\", \"inactive\"]}}}"
        );

        AsyncApiSchemaValidator.ValidationResult result = validator.validate(
            "{\"status\": \"unknown\"}", schema);

        assertThat(result.isValid(), is(false));
    }

    @Test
    public void shouldAcceptWhenNoSchema() {
        AsyncApiSchemaValidator.ValidationResult result = validator.validate("{\"any\": \"thing\"}", null);
        assertThat(result.isValid(), is(true));
    }

    @Test
    public void shouldRejectNullPayload() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\": \"object\"}");
        AsyncApiSchemaValidator.ValidationResult result = validator.validate(null, schema);
        assertThat(result.isValid(), is(false));
    }

    @Test
    public void shouldRejectBlankPayload() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\": \"object\"}");
        AsyncApiSchemaValidator.ValidationResult result = validator.validate("   ", schema);
        assertThat(result.isValid(), is(false));
    }

    @Test
    public void shouldValidateMinimum() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"score\": {\"type\": \"integer\", \"minimum\": 0, \"maximum\": 100}}}"
        );

        AsyncApiSchemaValidator.ValidationResult valid = validator.validate("{\"score\": 50}", schema);
        assertThat(valid.isValid(), is(true));

        AsyncApiSchemaValidator.ValidationResult invalid = validator.validate("{\"score\": -1}", schema);
        assertThat(invalid.isValid(), is(false));
    }

    @Test
    public void shouldValidatePattern() throws Exception {
        JsonNode schema = MAPPER.readTree(
            "{\"type\": \"object\", \"properties\": {\"code\": {\"type\": \"string\", \"pattern\": \"^[A-Z]{3}$\"}}}"
        );

        AsyncApiSchemaValidator.ValidationResult valid = validator.validate("{\"code\": \"ABC\"}", schema);
        assertThat(valid.isValid(), is(true));

        AsyncApiSchemaValidator.ValidationResult invalid = validator.validate("{\"code\": \"abc\"}", schema);
        assertThat(invalid.isValid(), is(false));
    }
}
