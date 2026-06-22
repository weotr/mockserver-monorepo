package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.openapi.examples.models.*;

import java.util.HashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.openapi.examples.ExampleBuilder.*;

/**
 * Tests that the {@code generateRealisticExampleValues} flag correctly switches between
 * static (default/OFF) and realistic (ON) example generation in {@link ExampleBuilder}.
 */
public class ExampleBuilderRealisticValuesTest {

    @Before
    public void setUp() {
        // Ensure the flag is off by default
        ConfigurationProperties.generateRealisticExampleValues(false);
    }

    @After
    public void tearDown() {
        // Always restore to default
        ConfigurationProperties.generateRealisticExampleValues(false);
    }

    // --- Flag OFF (default): static values ---

    @Test
    public void shouldReturnStaticEmailWhenFlagOff() {
        Example result = fromSchema(new EmailSchema(), new HashMap<>());
        assertThat(((StringExample) result).getValue(), is(SAMPLE_EMAIL_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticUuidWhenFlagOff() {
        Example result = fromSchema(new UUIDSchema(), new HashMap<>());
        assertThat(((StringExample) result).getValue(), is(SAMPLE_UUID_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticStringWhenFlagOff() {
        Example result = fromSchema(new StringSchema(), new HashMap<>());
        assertThat(((StringExample) result).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticBooleanWhenFlagOff() {
        Example result = fromSchema(new BooleanSchema(), new HashMap<>());
        assertThat(((BooleanExample) result).getValue(), is(SAMPLE_BOOLEAN_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticIntegerWhenFlagOff() {
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat("int32");
        Example result = fromSchema(schema, new HashMap<>());
        assertThat(((IntegerExample) result).getValue(), is(SAMPLE_INT_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticDateWhenFlagOff() {
        Example result = fromSchema(new DateSchema(), new HashMap<>());
        assertThat(((StringExample) result).getValue(), is(SAMPLE_DATE_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticDateTimeWhenFlagOff() {
        Example result = fromSchema(new DateTimeSchema(), new HashMap<>());
        assertThat(((StringExample) result).getValue(), is(SAMPLE_DATETIME_PROPERTY_VALUE));
    }

    @Test
    public void shouldReturnStaticByteWhenFlagOff() {
        Example result = fromSchema(new ByteArraySchema(), new HashMap<>());
        assertThat(((StringExample) result).getValue(), is(SAMPLE_BYTE_PROPERTY_VALUE));
    }

    // --- Flag ON: realistic values (different from static) ---

    @Test
    public void shouldReturnRealisticEmailWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result = fromSchema(new EmailSchema(), new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(not(SAMPLE_EMAIL_PROPERTY_VALUE)));
        assertThat(value, containsString("@"));
    }

    @Test
    public void shouldReturnRealisticUuidWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result = fromSchema(new UUIDSchema(), new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(not(SAMPLE_UUID_PROPERTY_VALUE)));
        assertThat(value.length(), is(36));
    }

    @Test
    public void shouldReturnRealisticStringWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result = fromSchema(new StringSchema(), new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(not(SAMPLE_STRING_PROPERTY_VALUE)));
        assertThat(value.length(), greaterThan(0));
    }

    @Test
    public void shouldReturnRealisticDateWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result = fromSchema(new DateSchema(), new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(not(SAMPLE_DATE_PROPERTY_VALUE)));
        // Should be a valid date format
        assertThat(value, matchesPattern("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void shouldReturnRealisticDateTimeWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result = fromSchema(new DateTimeSchema(), new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(not(SAMPLE_DATETIME_PROPERTY_VALUE)));
        assertThat(value, containsString("T"));
    }

    @Test
    public void shouldReturnRealisticByteWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result = fromSchema(new ByteArraySchema(), new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(not(SAMPLE_BYTE_PROPERTY_VALUE)));
        assertThat(value.length(), greaterThan(0));
    }

    // --- Determinism when flag ON ---

    @Test
    public void shouldProduceSameOutputAcrossCallsWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        Example result1 = fromSchema(new EmailSchema(), new HashMap<>());
        Example result2 = fromSchema(new EmailSchema(), new HashMap<>());
        // Both calls use the same fixed seed, so outputs should be identical
        assertThat(((StringExample) result1).getValue(), is(((StringExample) result2).getValue()));
    }

    // --- Explicit example/default/enum take priority even when flag ON ---

    @Test
    public void shouldPreferExplicitExampleEvenWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setExample("explicit_value");
        Example result = fromSchema(schema, new HashMap<>());
        assertThat(((StringExample) result).getValue(), is("explicit_value"));
    }

    @Test
    public void shouldPreferDefaultValueEvenWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setDefault("default_value");
        Example result = fromSchema(schema, new HashMap<>());
        assertThat(((StringExample) result).getValue(), is("default_value"));
    }

    @Test
    public void shouldPreferEnumValueEvenWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.addEnumItem("enum_first");
        schema.addEnumItem("enum_second");
        Example result = fromSchema(schema, new HashMap<>());
        assertThat(((StringExample) result).getValue(), is("enum_first"));
    }

    // --- Format-aware string generation when flag ON ---

    @Test
    public void shouldGenerateUriForUriFormatStringWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setFormat("uri");
        Example result = fromSchema(schema, new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(notNullValue()));
        assertThat(value.length(), greaterThan(0));
    }

    @Test
    public void shouldGenerateHostnameForHostnameFormatStringWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setFormat("hostname");
        Example result = fromSchema(schema, new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, containsString("."));
    }

    @Test
    public void shouldGenerateIpv4ForIpv4FormatStringWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setFormat("ipv4");
        Example result = fromSchema(schema, new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, matchesPattern("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"));
    }

    @Test
    public void shouldGenerateIpv6ForIpv6FormatStringWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setFormat("ipv6");
        Example result = fromSchema(schema, new HashMap<>());
        String value = ((StringExample) result).getValue();
        assertThat(value, is(notNullValue()));
        assertThat(value, containsString(":"));
    }

    // --- JSON-Schema constraints honoured when flag ON ---

    @Test
    public void shouldGeneratePatternMatchingStringWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        StringSchema schema = new StringSchema();
        schema.setPattern("SKU-[0-9]{6}");
        Example result = fromSchema(schema, new HashMap<>());
        assertThat(((StringExample) result).getValue(), matchesPattern("SKU-[0-9]{6}"));
    }

    @Test
    public void shouldEmitMinItemsArrayItemsWhenFlagOn() {
        ConfigurationProperties.generateRealisticExampleValues(true);
        io.swagger.v3.oas.models.media.ArraySchema schema = new io.swagger.v3.oas.models.media.ArraySchema();
        schema.setItems(new StringSchema());
        schema.setMinItems(3);
        Example result = fromSchema(schema, new HashMap<>());
        assertThat(((ArrayExample) result).getItems(), hasSize(3));
    }
}
