package org.mockserver.model;

import org.junit.Test;
import org.mockserver.uuid.UUIDService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.NottableSchemaString.notSchema;
import static org.mockserver.model.NottableSchemaString.schemaString;
import static org.mockserver.model.NottableString.string;

public class NottableSchemaStringEqualsTest {

    @Test
    public void shouldEqualWhenNull() {
        assertThat(schemaString(null), is(schemaString(null)));
        assertThat(schemaString("{ \"type\": \"string\" }"), not(schemaString(null)));
        assertThat(schemaString(null), not(schemaString("{ \"type\": \"string\" }")));
    }

    @Test
    public void shouldEqualForDoubleNegative() {
        assertThat(notSchema("{ \"type\": \"string\" }"), not(schemaString("{ \"type\": \"string\" }")));
        assertThat(notSchema("{ \"type\": \"string\" }"), not((Object) "{ \"type\": \"string\" }"));

        assertThat(schemaString("{ \"type\": \"string\" }"), not(schemaString("{" + NEW_LINE +
            "  \"type\": \"string\"," + NEW_LINE +
            "  \"minLength\": 2," + NEW_LINE +
            "  \"maxLength\": 3" + NEW_LINE +
            "}")));
        assertThat(schemaString("{ \"type\": \"string\" }"), not("{" + NEW_LINE +
            "  \"type\": \"string\"," + NEW_LINE +
            "  \"minLength\": 2," + NEW_LINE +
            "  \"maxLength\": 3" + NEW_LINE +
            "}"));

        assertThat(schemaString("{ \"type\": \"string\" }"), not(notSchema("{ \"type\": \"string\" }")));
    }

    @Test
    public void schemaForNumberShouldEqualString() {
        String schema = "{ \"type\": \"number\" }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("number schema.equals(string('1')) should be true", string.equals(string("1")), is(true));
        assertThat("number schema.equals(string('2.5')) should be true", string.equals(string("2.5")), is(true));
        assertThat("number schema.equals(string('a')) should be false", string.equals(string("a")), is(false));
        assertThat("NOT number schema.equals(string('1')) should be false", notString.equals(string("1")), is(false));
        assertThat("NOT number schema.equals(string('2.5')) should be false", notString.equals(string("2.5")), is(false));
        assertThat("NOT number schema.equals(string('a')) should be true", notString.equals(string("a")), is(true));
    }

    @Test
    public void schemaForIntegerShouldEqualString() {
        String schema = "{ \"type\": \"integer\" }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("integer schema.equals(string('1')) should be true", string.equals(string("1")), is(true));
        assertThat("integer schema.equals(string('a')) should be false", string.equals(string("a")), is(false));
        assertThat("NOT integer schema.equals(string('1')) should be false", notString.equals(string("1")), is(false));
        assertThat("NOT integer schema.equals(string('a')) should be true", notString.equals(string("a")), is(true));
    }

    @Test
    public void schemaForNumberMultipleShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "    \"type\"       : \"number\"," + NEW_LINE +
            "    \"multipleOf\" : 10" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("multipleOf:10.equals(string('10')) should be true", string.equals(string("10")), is(true));
        assertThat("multipleOf:10.equals(string('20')) should be true", string.equals(string("20")), is(true));
        assertThat("multipleOf:10.equals(string('23')) should be false", string.equals(string("23")), is(false));
        assertThat("NOT multipleOf:10.equals(string('10')) should be false", notString.equals(string("10")), is(false));
        assertThat("NOT multipleOf:10.equals(string('20')) should be false", notString.equals(string("20")), is(false));
        assertThat("NOT multipleOf:10.equals(string('23')) should be true", notString.equals(string("23")), is(true));
    }

    @Test
    public void schemaForStringByLengthShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "  \"type\": \"string\"," + NEW_LINE +
            "  \"minLength\": 2," + NEW_LINE +
            "  \"maxLength\": 3" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string[2..3].equals(string('abc')) should be true", string.equals(string("abc")), is(true));
        assertThat("string[2..3].equals(string('a')) should be false", string.equals(string("a")), is(false));
        assertThat("NOT string[2..3].equals(string('abc')) should be false", notString.equals(string("abc")), is(false));
        assertThat("NOT string[2..3].equals(string('a')) should be true", notString.equals(string("a")), is(true));
    }

    @Test
    public void schemaForStringByRegexShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "   \"type\": \"string\"," + NEW_LINE +
            "   \"pattern\": \"^(\\\\([0-9]{3}\\\\))?[0-9]{3}-[0-9]{4}$\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("phone regex.equals(string('555-1212')) should be true", string.equals(string("555-1212")), is(true));
        assertThat("phone regex.equals(string('(888)555-1212')) should be true", string.equals(string("(888)555-1212")), is(true));
        assertThat("phone regex.equals(string('(888)555-1212 ext. 532')) should be false", string.equals(string("(888)555-1212 ext. 532")), is(false));
        assertThat("phone regex.equals(string('(800)FLOWERS')) should be false", string.equals(string("(800)FLOWERS")), is(false));
        assertThat("NOT phone regex.equals(string('555-1212')) should be false", notString.equals(string("555-1212")), is(false));
        assertThat("NOT phone regex.equals(string('(888)555-1212')) should be false", notString.equals(string("(888)555-1212")), is(false));
        assertThat("NOT phone regex.equals(string('(888)555-1212 ext. 532')) should be true", notString.equals(string("(888)555-1212 ext. 532")), is(true));
        assertThat("NOT phone regex.equals(string('(800)FLOWERS')) should be true", notString.equals(string("(800)FLOWERS")), is(true));
    }

    @Test
    public void schemaForStringByUUIDPatternShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"uuid\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("uuid format.equals(valid UUID) should be true", string.equals(string(UUIDService.getUUID())), is(true));
        assertThat("uuid format.equals(string('abc')) should be false", string.equals(string("abc")), is(false));
        assertThat("NOT uuid format.equals(valid UUID) should be false", notString.equals(string(UUIDService.getUUID())), is(false));
        assertThat("NOT uuid format.equals(string('abc')) should be true", notString.equals(string("abc")), is(true));
    }

    @Test
    public void schemaForStringByEmailPatternShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"email\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("email format.equals(string('someone@mockserver.com')) should be true", string.equals(string("someone@mockserver.com")), is(true));
        assertThat("email format.equals(string('abc')) should be false", string.equals(string("abc")), is(false));
        assertThat("NOT email format.equals(string('someone@mockserver.com')) should be false", notString.equals(string("someone@mockserver.com")), is(false));
        assertThat("NOT email format.equals(string('abc')) should be true", notString.equals(string("abc")), is(true));
    }

    @Test
    public void schemaForStringByIPv4PatternShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"ipv4\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("ipv4 format.equals(string('192.168.1.30')) should be true", string.equals(string("192.168.1.30")), is(true));
        assertThat("ipv4 format.equals(string('abc')) should be false", string.equals(string("abc")), is(false));
        assertThat("NOT ipv4 format.equals(string('192.168.1.30')) should be false", notString.equals(string("192.168.1.30")), is(false));
        assertThat("NOT ipv4 format.equals(string('abc')) should be true", notString.equals(string("abc")), is(true));
    }

    @Test
    public void schemaForStringByHostnamePatternShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"hostname\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("hostname format.equals(string('mock-server.com')) should be true", string.equals(string("mock-server.com")), is(true));
        assertThat("hostname format.equals(string('123%£45')) should be false", string.equals(string("123%£45")), is(false));
        assertThat("NOT hostname format.equals(string('mock-server.com')) should be false", notString.equals(string("mock-server.com")), is(false));
        assertThat("NOT hostname format.equals(string('12@&345')) should be true", notString.equals(string("12@&345")), is(true));
    }

    @Test
    public void schemaForStringByDateTimePatternShouldEqualString() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"date-time\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("date-time format.equals(ISO-8601 string) should be true", string.equals(string("2018-11-13T20:20:39+00:00")), is(true));
        assertThat("date-time format.equals(string('2018-11-13 20:20:39')) should be false", string.equals(string("2018-11-13 20:20:39")), is(false));
        assertThat("NOT date-time format.equals(ISO-8601 string) should be false", notString.equals(string("2018-11-13T20:20:39+00:00")), is(false));
        assertThat("NOT date-time format.equals(string('2018-11-13 20:20:39')) should be true", notString.equals(string("2018-11-13 20:20:39")), is(true));
    }

    @Test
    public void shouldEqualSchemaForNumber() {
        String schema = "{ \"type\": \"number\" }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('1').equals(number schema) should be true", string("1").equals(string), is(true));
        assertThat("string('2.5').equals(number schema) should be true", string("2.5").equals(string), is(true));
        assertThat("string('a').equals(number schema) should be false", string("a").equals(string), is(false));
        assertThat("string('1').equals(NOT number schema) should be false", string("1").equals(notString), is(false));
        assertThat("string('2.5').equals(NOT number schema) should be false", string("2.5").equals(notString), is(false));
        assertThat("string('a').equals(NOT number schema) should be true", string("a").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForInteger() {
        String schema = "{ \"type\": \"integer\" }";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('1').equals(integer schema) should be true", string("1").equals(string), is(true));
        assertThat("string('a').equals(integer schema) should be false", string("a").equals(string), is(false));
        assertThat("string('1').equals(NOT integer schema) should be false", string("1").equals(notString), is(false));
        assertThat("string('a').equals(NOT integer schema) should be true", string("a").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForNumberMultiple() {
        String schema = "{" + NEW_LINE +
            "    \"type\"       : \"number\"," + NEW_LINE +
            "    \"multipleOf\" : 10" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('10').equals(multipleOf:10 schema) should be true", string("10").equals(string), is(true));
        assertThat("string('20').equals(multipleOf:10 schema) should be true", string("20").equals(string), is(true));
        assertThat("string('23').equals(multipleOf:10 schema) should be false", string("23").equals(string), is(false));
        assertThat("string('10').equals(NOT multipleOf:10 schema) should be false", string("10").equals(notString), is(false));
        assertThat("string('20').equals(NOT multipleOf:10 schema) should be false", string("20").equals(notString), is(false));
        assertThat("string('23').equals(NOT multipleOf:10 schema) should be true", string("23").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByLength() {
        String schema = "{" + NEW_LINE +
            "  \"type\": \"string\"," + NEW_LINE +
            "  \"minLength\": 2," + NEW_LINE +
            "  \"maxLength\": 3" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('abc').equals(string[2..3] schema) should be true", string("abc").equals(string), is(true));
        assertThat("string('a').equals(string[2..3] schema) should be false", string("a").equals(string), is(false));
        assertThat("string('abc').equals(NOT string[2..3] schema) should be false", string("abc").equals(notString), is(false));
        assertThat("string('a').equals(NOT string[2..3] schema) should be true", string("a").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByRegex() {
        String schema = "{" + NEW_LINE +
            "   \"type\": \"string\"," + NEW_LINE +
            "   \"pattern\": \"^(\\\\([0-9]{3}\\\\))?[0-9]{3}-[0-9]{4}$\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('555-1212').equals(phone regex) should be true", string("555-1212").equals(string), is(true));
        assertThat("string('(888)555-1212').equals(phone regex) should be true", string("(888)555-1212").equals(string), is(true));
        assertThat("string('(888)555-1212 ext. 532').equals(phone regex) should be false", string("(888)555-1212 ext. 532").equals(string), is(false));
        assertThat("string('(800)FLOWERS').equals(phone regex) should be false", string("(800)FLOWERS").equals(string), is(false));
        assertThat("string('555-1212').equals(NOT phone regex) should be false", string("555-1212").equals(notString), is(false));
        assertThat("string('(888)555-1212').equals(NOT phone regex) should be false", string("(888)555-1212").equals(notString), is(false));
        assertThat("string('(888)555-1212 ext. 532').equals(NOT phone regex) should be true", string("(888)555-1212 ext. 532").equals(notString), is(true));
        assertThat("string('(800)FLOWERS').equals(NOT phone regex) should be true", string("(800)FLOWERS").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByUUIDPattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"uuid\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("uuid format schema.equals(valid UUID) should be true", string.equals(string(UUIDService.getUUID())), is(true));
        assertThat("string('abc').equals(uuid format) should be false", string("abc").equals(string), is(false));
        assertThat("NOT uuid format schema.equals(valid UUID) should be false", notString.equals(string(UUIDService.getUUID())), is(false));
        assertThat("string('abc').equals(NOT uuid format) should be true", string("abc").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByEmailPattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"email\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('someone@mockserver.com').equals(email format) should be true", string("someone@mockserver.com").equals(string), is(true));
        assertThat("string('abc').equals(email format) should be false", string("abc").equals(string), is(false));
        assertThat("string('someone@mockserver.com').equals(NOT email format) should be false", string("someone@mockserver.com").equals(notString), is(false));
        assertThat("string('abc').equals(NOT email format) should be true", string("abc").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByIPv4Pattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"ipv4\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('192.168.1.30').equals(ipv4 format) should be true", string("192.168.1.30").equals(string), is(true));
        assertThat("string('abc').equals(ipv4 format) should be false", string("abc").equals(string), is(false));
        assertThat("string('192.168.1.30').equals(NOT ipv4 format) should be false", string("192.168.1.30").equals(notString), is(false));
        assertThat("string('abc').equals(NOT ipv4 format) should be true", string("abc").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByHostnamePattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"hostname\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string('mock-server.com').equals(hostname format) should be true", string("mock-server.com").equals(string), is(true));
        assertThat("string('12*&345').equals(hostname format) should be false", string("12*&345").equals(string), is(false));
        assertThat("string('mock-server.com').equals(NOT hostname format) should be false", string("mock-server.com").equals(notString), is(false));
        assertThat("string('1234%)5').equals(NOT hostname format) should be true", string("1234%)5").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForStringByDateTimePattern() {
        String schema = "{" + NEW_LINE +
            "    \"type\": \"string\"," + NEW_LINE +
            "    \"format\": \"date-time\"" + NEW_LINE +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("string(ISO-8601).equals(date-time format) should be true", string("2018-11-13T20:20:39+00:00").equals(string), is(true));
        assertThat("string('2018-11-13 20:20:39').equals(date-time format) should be false", string("2018-11-13 20:20:39").equals(string), is(false));
        assertThat("string(ISO-8601).equals(NOT date-time format) should be false", string("2018-11-13T20:20:39+00:00").equals(notString), is(false));
        assertThat("string('2018-11-13 20:20:39').equals(NOT date-time format) should be true", string("2018-11-13 20:20:39").equals(notString), is(true));
    }

    @Test
    public void shouldEqualSchemaForArrayOfItems() {
        String schema = "{" + NEW_LINE +
            "    \"items\": {" +
            "        \"description\":\"Agent ID\"," +
            "        \"format\":\"numbers\"," +
            "        \"minLength\":3," +
            "        \"type\":\"string\"" +
            "    }," +
            "    \"type\":\"array\"" +
            "}";
        NottableSchemaString string = schemaString(schema);
        NottableSchemaString notString = schemaString("!" + schema);
        assertThat("array schema: both items valid (len>=3) should be true", string("[\"000\",\"000\"]").equals(string), is(true));
        assertThat("array schema: first item too short should be false", string("[\"00\",\"000\"]").equals(string), is(false));
        assertThat("array schema: second item too short should be false", string("[\"000\",\"00\"]").equals(string), is(false));
        assertThat("NOT array schema: both items valid should be false", string("[\"000\",\"000\"]").equals(notString), is(false));
        assertThat("NOT array schema: first item too short should be true", string("[\"00\",\"000\"]").equals(notString), is(true));
        assertThat("NOT array schema: second item too short should be true", string("[\"000\",\"00\"]").equals(notString), is(true));
    }
}
