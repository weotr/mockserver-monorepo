package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class JsonSchemaMatcherTest {

    public static final String JSON_SCHEMA = "{" + NEW_LINE +
        "    \"type\": \"object\"," + NEW_LINE +
        "    \"properties\": {" + NEW_LINE +
        "        \"enumField\": {" + NEW_LINE +
        "            \"enum\": [ \"one\", \"two\" ]" + NEW_LINE +
        "        }," + NEW_LINE +
        "        \"arrayField\": {" + NEW_LINE +
        "            \"type\": \"array\"," + NEW_LINE +
        "            \"minItems\": 1," + NEW_LINE +
        "            \"items\": {" + NEW_LINE +
        "                \"type\": \"string\"" + NEW_LINE +
        "            }," + NEW_LINE +
        "            \"uniqueItems\": true" + NEW_LINE +
        "        }," + NEW_LINE +
        "        \"stringField\": {" + NEW_LINE +
        "            \"type\": \"string\"," + NEW_LINE +
        "            \"minLength\": 5," + NEW_LINE +
        "            \"maxLength\": 6" + NEW_LINE +
        "        }," + NEW_LINE +
        "        \"booleanField\": {" + NEW_LINE +
        "            \"type\": \"boolean\"" + NEW_LINE +
        "        }," + NEW_LINE +
        "        \"objectField\": {" + NEW_LINE +
        "            \"type\": \"object\"," + NEW_LINE +
        "            \"properties\": {" + NEW_LINE +
        "                \"stringField\": {" + NEW_LINE +
        "                    \"type\": \"string\"," + NEW_LINE +
        "                    \"minLength\": 1," + NEW_LINE +
        "                    \"maxLength\": 3" + NEW_LINE +
        "                }" + NEW_LINE +
        "            }," + NEW_LINE +
        "            \"required\": [ \"stringField\" ]" + NEW_LINE +
        "        }" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"additionalProperties\" : false," + NEW_LINE +
        "    \"required\": [ \"enumField\", \"arrayField\" ]" + NEW_LINE +
        "}";

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test
    public void shouldMatchValidJson() {
        // given
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String validJson = "{\"enumField\": \"one\", \"arrayField\": [\"item\"]}";

        // then - valid document matches
        assertThat("valid JSON with required fields should match schema", matcher.matches(null, validJson), is(true));
    }

    @Test
    public void shouldMatchValidJsonWithAllFields() {
        // given
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String validJson = "{" +
            "\"enumField\": \"two\", " +
            "\"arrayField\": [\"a\", \"b\"], " +
            "\"stringField\": \"abcde\", " +
            "\"booleanField\": true, " +
            "\"objectField\": {\"stringField\": \"abc\"}" +
            "}";

        // then
        assertThat("valid JSON with all fields should match schema", matcher.matches(null, validJson), is(true));
    }

    @Test
    public void shouldMatchWhenJsonEqualsSchema() {
        // given - matcher also returns true when matched string equals the schema itself
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);

        // then
        assertThat("JSON that equals the schema itself should match", matcher.matches(null, JSON_SCHEMA), is(true));
    }

    @Test
    public void shouldNotMatchInvalidJson() {
        // given - missing required fields
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String invalidJson = "{\"booleanField\": true}";

        // when
        assertThat("JSON missing required fields 'enumField'+'arrayField' should not match", matcher.matches(new MatchDifference(false, request()), invalidJson), is(false));
    }

    @Test
    public void shouldNotMatchJsonWithWrongEnumValue() {
        // given - enumField has value not in enum list
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String invalidJson = "{\"enumField\": \"three\", \"arrayField\": [\"item\"]}";

        // when
        assertThat("enumField value 'three' not in [one,two] should not match", matcher.matches(new MatchDifference(false, request()), invalidJson), is(false));
    }

    @Test
    public void shouldNotMatchJsonWithAdditionalProperties() {
        // given - additionalProperties is false, so extra fields are not allowed
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String invalidJson = "{\"enumField\": \"one\", \"arrayField\": [\"item\"], \"extraField\": \"value\"}";

        // when
        assertThat("JSON with additional property 'extraField' should not match", matcher.matches(new MatchDifference(false, request()), invalidJson), is(false));
    }

    @Test
    public void shouldNotMatchJsonWithEmptyArray() {
        // given - arrayField requires minItems: 1
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String invalidJson = "{\"enumField\": \"one\", \"arrayField\": []}";

        // when
        assertThat("empty arrayField (minItems:1) should not match", matcher.matches(new MatchDifference(false, request()), invalidJson), is(false));
    }

    @Test
    public void shouldNotMatchBlankString() {
        // given
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);

        // then - blank string does not match
        assertThat("blank string should not match JSON schema", matcher.matches(new MatchDifference(false, request()), ""), is(false));
    }

    @Test
    public void shouldNotMatchNull() {
        // given
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);

        // then - null does not match
        assertThat("null should not match JSON schema", matcher.matches(new MatchDifference(false, request()), null), is(false));
    }

    @Test
    public void shouldRecordDifferenceWhenNotMatching() {
        // given
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String invalidJson = "{\"booleanField\": true}";
        MatchDifference matchDifference = new MatchDifference(true, request());
        matchDifference.currentField(MatchDifference.Field.BODY);

        // when
        assertThat("invalid JSON should not match schema", matcher.matches(matchDifference, invalidJson), is(false));

        // then - a difference was recorded containing schema-related diagnostic
        assertThat("match differences should be recorded for BODY field", matchDifference.getDifferences(MatchDifference.Field.BODY).isEmpty(), is(false));
    }

    @Test
    public void shouldHandleException() {
        // given - malformed JSON that causes a parsing exception
        JsonSchemaMatcher matcher = new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA);
        String malformedJson = "not valid json {{{";
        MatchDifference matchDifference = new MatchDifference(true, request());
        matchDifference.currentField(MatchDifference.Field.BODY);

        // when - the matcher catches the exception and returns false
        assertThat("malformed JSON should not match schema", matcher.matches(matchDifference, malformedJson), is(false));

        // then - a difference was recorded
        assertThat("match differences should be recorded for malformed JSON", matchDifference.getDifferences(MatchDifference.Field.BODY).isEmpty(), is(false));
    }

    @Test
    public void showHaveCorrectEqualsBehaviour() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        assertEquals(new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA), new JsonSchemaMatcher(mockServerLogger, JSON_SCHEMA));
    }
}
