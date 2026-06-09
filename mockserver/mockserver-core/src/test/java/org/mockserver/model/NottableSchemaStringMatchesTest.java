package org.mockserver.model;

import org.junit.Test;
import org.mockserver.uuid.UUIDService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.NottableSchemaString.schemaString;

public class NottableSchemaStringMatchesTest {

    @Test
    public void shouldMatchNumber() {
        String schema = "{ \"type\": \"number\" }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("number schema should match '1'", string.matches("1"), is(true));
        assertThat("number schema should match '2.5'", string.matches("2.5"), is(true));
        assertThat("number schema should not match 'a'", string.matches("a"), is(false));
        assertThat("NOT number schema should not match '1'", notString.matches("1"), is(false));
        assertThat("NOT number schema should not match '2.5'", notString.matches("2.5"), is(false));
        assertThat("NOT number schema should match 'a'", notString.matches("a"), is(true));
    }

    @Test
    public void shouldMatchInteger() {
        String schema = "{ \"type\": \"integer\" }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("integer schema should match '1'", string.matches("1"), is(true));
        assertThat("integer schema should not match 'a'", string.matches("a"), is(false));
        assertThat("NOT integer schema should not match '1'", notString.matches("1"), is(false));
        assertThat("NOT integer schema should match 'a'", notString.matches("a"), is(true));
    }

    @Test
    public void shouldMatchNullableInteger() {
        String schema = "{ \"type\": \"integer\", \"nullable\": true }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("nullable integer schema should match '1'", string.matches("1"), is(true));
        assertThat("nullable integer schema should match empty string", string.matches(""), is(true));
        assertThat("nullable integer schema should not match 'a'", string.matches("a"), is(false));
        assertThat("NOT nullable integer schema should not match '1'", notString.matches("1"), is(false));
        assertThat("NOT nullable integer schema should not match empty string", notString.matches(""), is(false));
        assertThat("NOT nullable integer schema should match 'a'", notString.matches("a"), is(true));
    }

    @Test
    public void shouldMatchNumberMultiple() {
        String schema = "{" + NEW_LINE +
            "    \"type\"       : \"number\"," + NEW_LINE +
            "    \"multipleOf\" : 10" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("multipleOf:10 schema should match '10'", string.matches("10"), is(true));
        assertThat("multipleOf:10 schema should match '20'", string.matches("20"), is(true));
        assertThat("multipleOf:10 schema should not match '23'", string.matches("23"), is(false));
        assertThat("NOT multipleOf:10 schema should not match '10'", notString.matches("10"), is(false));
        assertThat("NOT multipleOf:10 schema should not match '20'", notString.matches("20"), is(false));
        assertThat("NOT multipleOf:10 schema should match '23'", notString.matches("23"), is(true));
    }

    @Test
    public void shouldMatchStringByLength() {
        String schema = "{" + NEW_LINE +
            "  \"type\": \"string\"," + NEW_LINE +
            "  \"minLength\": 2," + NEW_LINE +
            "  \"maxLength\": 3" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string[2..3] schema should match 'abc' (len=3)", string.matches("abc"), is(true));
        assertThat("string[2..3] schema should not match 'a' (len=1)", string.matches("a"), is(false));
        assertThat("NOT string[2..3] schema should not match 'abc'", notString.matches("abc"), is(false));
        assertThat("NOT string[2..3] schema should match 'a'", notString.matches("a"), is(true));
    }

    @Test
    public void shouldMatchNullableStringByLength() {
        String schema = "{" + NEW_LINE +
            "  \"type\": \"string\"," + NEW_LINE +
            "  \"minLength\": 2," + NEW_LINE +
            "  \"maxLength\": 3," + NEW_LINE +
            "  \"nullable\": true" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("nullable string[2..3] schema should match 'abc'", string.matches("abc"), is(true));
        assertThat("nullable string[2..3] schema should match empty string", string.matches(""), is(true));
        assertThat("nullable string[2..3] schema should not match 'a' (len=1)", string.matches("a"), is(false));
        assertThat("NOT nullable string[2..3] schema should not match 'abc'", notString.matches("abc"), is(false));
        assertThat("NOT nullable string[2..3] schema should not match empty string", notString.matches(""), is(false));
        assertThat("NOT nullable string[2..3] schema should match 'a'", notString.matches("a"), is(true));
    }

    @Test
    public void shouldMatchStringByRegex() {
        String schema = "{" + NEW_LINE +
            "   \"type\": \"string\"," + NEW_LINE +
            "   \"pattern\": \"^(\\\\([0-9]{3}\\\\))?[0-9]{3}-[0-9]{4}$\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("phone regex should match '555-1212'", string.matches("555-1212"), is(true));
        assertThat("phone regex should match '(888)555-1212'", string.matches("(888)555-1212"), is(true));
        assertThat("phone regex should not match '(888)555-1212 ext. 532'", string.matches("(888)555-1212 ext. 532"), is(false));
        assertThat("phone regex should not match '(800)FLOWERS'", string.matches("(800)FLOWERS"), is(false));
        assertThat("NOT phone regex should not match '555-1212'", notString.matches("555-1212"), is(false));
        assertThat("NOT phone regex should not match '(888)555-1212'", notString.matches("(888)555-1212"), is(false));
        assertThat("NOT phone regex should match '(888)555-1212 ext. 532'", notString.matches("(888)555-1212 ext. 532"), is(true));
        assertThat("NOT phone regex should match '(800)FLOWERS'", notString.matches("(800)FLOWERS"), is(true));
    }

    @Test
    public void shouldMatchStringByUUIDPattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"uuid\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("uuid format should match valid UUID", string.matches(UUIDService.getUUID()), is(true));
        assertThat("uuid format should not match 'abc'", string.matches("abc"), is(false));
        assertThat("NOT uuid format should not match valid UUID", notString.matches(UUIDService.getUUID()), is(false));
        assertThat("NOT uuid format should match 'abc'", notString.matches("abc"), is(true));
    }

    @Test
    public void shouldMatchStringByEmailPattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"email\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("email format should match 'someone@mockserver.com'", string.matches("someone@mockserver.com"), is(true));
        assertThat("email format should not match 'abc'", string.matches("abc"), is(false));
        assertThat("NOT email format should not match 'someone@mockserver.com'", notString.matches("someone@mockserver.com"), is(false));
        assertThat("NOT email format should match 'abc'", notString.matches("abc"), is(true));
    }

    @Test
    public void shouldMatchStringByIPv4Pattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"ipv4\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("ipv4 format should match '192.168.1.30'", string.matches("192.168.1.30"), is(true));
        assertThat("ipv4 format should not match 'abc'", string.matches("abc"), is(false));
        assertThat("NOT ipv4 format should not match '192.168.1.30'", notString.matches("192.168.1.30"), is(false));
        assertThat("NOT ipv4 format should match 'abc'", notString.matches("abc"), is(true));
    }

    @Test
    public void shouldMatchStringByHostnamePattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"hostname\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("hostname format should match 'mock-server.com'", string.matches("mock-server.com"), is(true));
        assertThat("hostname format should not match '%@12345'", string.matches("%@12345"), is(false));
        assertThat("NOT hostname format should not match 'mock-server.com'", notString.matches("mock-server.com"), is(false));
        assertThat("NOT hostname format should match '12$^345'", notString.matches("12$^345"), is(true));
    }

    @Test
    public void shouldMatchStringByDateTimePattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"date-time\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("date-time format should match ISO-8601", string.matches("2018-11-13T20:20:39+00:00"), is(true));
        assertThat("date-time format should not match '2018-11-13 20:20:39'", string.matches("2018-11-13 20:20:39"), is(false));
        assertThat("NOT date-time format should not match ISO-8601", notString.matches("2018-11-13T20:20:39+00:00"), is(false));
        assertThat("NOT date-time format should match '2018-11-13 20:20:39'", notString.matches("2018-11-13 20:20:39"), is(true));
    }
}
