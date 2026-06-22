/*
 *  Copyright 2017 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.*;
import org.junit.Test;
import org.mockserver.openapi.examples.models.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.openapi.examples.ExampleBuilder.*;

/**
 * Deterministic (flag-off) coverage of {@link ExampleBuilder} default example generation.
 *
 * <p>Every test runs with {@code generateRealisticExampleValues} OFF (the default), so no
 * global configuration state is mutated and this test belongs in the parallel phase.
 *
 * <p>Locks in the correct default value for each primitive, format, container, composition and
 * OAS-3.1 form so regressions (e.g. a no-format {@code default}/{@code enum} being discarded, or
 * an integer-overflow author example being swallowed) are caught.
 */
public class ExampleBuilderTest {

    private static final Map<String, Schema> NO_DEFINITIONS = new HashMap<>();

    private static Example build(Schema<?> schema) {
        return fromSchema(schema, NO_DEFINITIONS);
    }

    // --- strings ---

    @Test
    public void shouldGenerateString() {
        assertThat(((StringExample) build(new StringSchema())).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateDate() {
        assertThat(((StringExample) build(new DateSchema())).getValue(), is(SAMPLE_DATE_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateDateTime() {
        assertThat(((StringExample) build(new DateTimeSchema())).getValue(), is(SAMPLE_DATETIME_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateEmail() {
        assertThat(((StringExample) build(new EmailSchema())).getValue(), is(SAMPLE_EMAIL_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateUuid() {
        assertThat(((StringExample) build(new UUIDSchema())).getValue(), is(SAMPLE_UUID_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateByte() {
        assertThat(((StringExample) build(new ByteArraySchema())).getValue(), is(SAMPLE_BYTE_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateUri() {
        StringSchema schema = new StringSchema();
        schema.setFormat("uri");
        // flag off: uri format falls through to the plain sample string
        assertThat(((StringExample) build(schema)).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldGeneratePassword() {
        assertThat(((StringExample) build(new PasswordSchema())).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldUseStringDefault() {
        StringSchema schema = new StringSchema();
        schema.setDefault("the-default");
        assertThat(((StringExample) build(schema)).getValue(), is("the-default"));
    }

    @Test
    public void shouldUseStringEnum() {
        StringSchema schema = new StringSchema();
        schema.setEnum(List.of("first", "second"));
        assertThat(((StringExample) build(schema)).getValue(), is("first"));
    }

    // --- integers ---

    @Test
    public void shouldGenerateInt32() {
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat("int32");
        assertThat(((IntegerExample) build(schema)).getValue(), is(SAMPLE_INT_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateInt64() {
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat("int64");
        assertThat(((LongExample) build(schema)).getValue(), is((long) SAMPLE_LONG_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateNoFormatInteger() {
        // a plain integer (no format) with no default/enum yields the base-integer sample
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat(null);
        assertThat(((IntegerExample) build(schema)).getValue(), is(SAMPLE_BASE_INTEGER_PROPERTY_VALUE));
    }

    @Test
    public void shouldUseNoFormatIntegerDefault() {
        // Fix 1: a no-format integer with a default must use the default, not 0
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat(null);
        schema.setDefault(new BigDecimal(5));
        assertThat(((IntegerExample) build(schema)).getValue(), is(5));
    }

    @Test
    public void shouldUseNoFormatIntegerEnum() {
        // Fix 1: a no-format integer with an enum must use the first enum value, not 0
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat(null);
        schema.setEnum(List.of(new BigDecimal(7), new BigDecimal(8)));
        assertThat(((IntegerExample) build(schema)).getValue(), is(7));
    }

    @Test
    public void shouldPreserveNoFormatIntegerOverflowExample() {
        // Fix 3: an author example too large for int must be preserved (via the Long fallback),
        // not swallowed by Integer.parseInt and replaced with a generated value.
        // (IntegerSchema retains a Long example; a value beyond long range is coerced to null by
        // the swagger model before it reaches ExampleBuilder, so Long is the meaningful boundary.)
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat(null);
        schema.setExample(3000000000L);
        Example result = build(schema);
        assertThat(result, is(instanceOf(LongExample.class)));
        assertThat(((LongExample) result).getValue(), is(3000000000L));
    }

    @Test
    public void shouldPreserveNoFormatIntegerHugeOverflowExampleAsBigInteger() {
        // Fix 3: when an example string exceeds even the long range, the no-format integer branch
        // falls back to BigInteger rather than dropping the author example. A generic Schema is used
        // because IntegerSchema coerces an out-of-long-range example to null before ExampleBuilder runs.
        IntegerSchema integerSchema = new IntegerSchema() {
            @Override
            public Object getExample() {
                return new BigInteger("100000000000000000000");
            }
        };
        integerSchema.setFormat(null);
        Example result = build(integerSchema);
        assertThat(result, is(instanceOf(BigIntegerExample.class)));
        assertThat(((BigIntegerExample) result).getValue(), is(new BigInteger("100000000000000000000")));
    }

    // --- numbers ---

    @Test
    public void shouldGenerateFloat() {
        NumberSchema schema = new NumberSchema();
        schema.setFormat("float");
        assertThat(((FloatExample) build(schema)).getValue(), is(SAMPLE_FLOAT_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateDouble() {
        NumberSchema schema = new NumberSchema();
        schema.setFormat("double");
        // Fix 3: the double sample must be exactly 1.1, not the float-widened 1.100000023841858
        assertThat(((DoubleExample) build(schema)).getValue(), is(1.1));
        assertThat(SAMPLE_DOUBLE_PROPERTY_VALUE, is(1.1));
    }

    @Test
    public void shouldGenerateNoFormatNumber() {
        NumberSchema schema = new NumberSchema();
        schema.setFormat(null);
        assertThat(((DecimalExample) build(schema)).getValue(), is(new BigDecimal(SAMPLE_DECIMAL_PROPERTY_VALUE)));
    }

    @Test
    public void shouldUseNoFormatNumberDefault() {
        // Fix 1: a no-format number with a default must use the default
        NumberSchema schema = new NumberSchema();
        schema.setFormat(null);
        schema.setDefault(new BigDecimal("3.14"));
        assertThat(((DecimalExample) build(schema)).getValue(), is(new BigDecimal("3.14")));
    }

    @Test
    public void shouldUseNoFormatNumberEnum() {
        // Fix 1: a no-format number with an enum must use the first enum value
        NumberSchema schema = new NumberSchema();
        schema.setFormat(null);
        schema.setEnum(List.of(new BigDecimal("2.5"), new BigDecimal("9.9")));
        assertThat(((DecimalExample) build(schema)).getValue(), is(new BigDecimal("2.5")));
    }

    // --- boolean ---

    @Test
    public void shouldGenerateBoolean() {
        assertThat(((BooleanExample) build(new BooleanSchema())).getValue(), is(SAMPLE_BOOLEAN_PROPERTY_VALUE));
    }

    // --- arrays ---

    @Test
    public void shouldGenerateArrayOfScalar() {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        Example result = build(schema);
        assertThat(result, is(instanceOf(ArrayExample.class)));
        List<Example> items = ((ArrayExample) result).getItems();
        assertThat(items, hasSize(1));
        assertThat(((StringExample) items.get(0)).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateArrayOfObject() {
        ObjectSchema item = new ObjectSchema();
        item.addProperty("name", new StringSchema());
        ArraySchema schema = new ArraySchema();
        schema.setItems(item);
        Example result = build(schema);
        assertThat(result, is(instanceOf(ArrayExample.class)));
        List<Example> items = ((ArrayExample) result).getItems();
        assertThat(items, hasSize(1));
        assertThat(items.get(0), is(instanceOf(ObjectExample.class)));
        assertThat(((ObjectExample) items.get(0)).keySet(), hasItem("name"));
    }

    // --- objects ---

    @Test
    public void shouldGenerateNestedObject() {
        ObjectSchema inner = new ObjectSchema();
        inner.addProperty("city", new StringSchema());
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("name", new StringSchema());
        schema.addProperty("address", inner);
        Example result = build(schema);
        assertThat(result, is(instanceOf(ObjectExample.class)));
        ObjectExample object = (ObjectExample) result;
        assertThat(object.keySet(), hasItems("name", "address"));
        assertThat(object.get("address"), is(instanceOf(ObjectExample.class)));
        assertThat(((ObjectExample) object.get("address")).keySet(), hasItem("city"));
    }

    // --- additionalProperties ---

    @Test
    public void shouldGenerateAdditionalPropertiesFromSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(new StringSchema());
        Example result = build(schema);
        assertThat(result, is(instanceOf(ObjectExample.class)));
        ObjectExample object = (ObjectExample) result;
        assertThat(object.keySet(), hasItems("additionalProp1", "additionalProp2", "additionalProp3"));
    }

    @Test
    public void shouldGenerateEmptyObjectForAdditionalPropertiesTrue() {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(Boolean.TRUE);
        Example result = build(schema);
        assertThat(result, is(instanceOf(ObjectExample.class)));
    }

    // --- composition ---

    @Test
    public void shouldMergeAllOfObjects() {
        ObjectSchema a = new ObjectSchema();
        a.addProperty("x", new StringSchema());
        ObjectSchema b = new ObjectSchema();
        b.addProperty("y", new IntegerSchema());
        ComposedSchema schema = new ComposedSchema();
        schema.addAllOfItem(a);
        schema.addAllOfItem(b);
        Example result = build(schema);
        assertThat(result, is(instanceOf(ObjectExample.class)));
        assertThat(((ObjectExample) result).keySet(), hasItems("x", "y"));
    }

    @Test
    public void shouldUseFirstAnyOfMember() {
        ComposedSchema schema = new ComposedSchema();
        StringSchema first = new StringSchema();
        first.setDefault("anyOf-first");
        schema.addAnyOfItem(first);
        schema.addAnyOfItem(new IntegerSchema());
        Example result = build(schema);
        assertThat(result, is(instanceOf(StringExample.class)));
        assertThat(((StringExample) result).getValue(), is("anyOf-first"));
    }

    @Test
    public void shouldUseFirstOneOfMember() {
        ComposedSchema schema = new ComposedSchema();
        StringSchema first = new StringSchema();
        first.setDefault("oneOf-first");
        schema.addOneOfItem(first);
        schema.addOneOfItem(new IntegerSchema());
        Example result = build(schema);
        assertThat(result, is(instanceOf(StringExample.class)));
        assertThat(((StringExample) result).getValue(), is("oneOf-first"));
    }

    // --- OAS 3.1 type arrays ---

    @Test
    public void shouldGenerateForOas31NullableTypeArray() {
        // type: [string, "null"] — pick the first non-null type (string)
        Schema<?> schema = new Schema<>();
        schema.setTypes(new java.util.LinkedHashSet<>(List.of("string", "null")));
        Example result = build(schema);
        assertThat(result, is(instanceOf(StringExample.class)));
        assertThat(((StringExample) result).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldHonourPatternForOas31TypeArrayString() {
        // type: [string] with a pattern — the OAS 3.1 type-array path must honour the pattern
        // with the same priority as the typed StringSchema path
        Schema<?> schema = new Schema<>();
        schema.setTypes(new java.util.LinkedHashSet<>(List.of("string")));
        schema.setPattern("[A-Z]{2}\\d{3}");
        Example result = build(schema);
        assertThat(((StringExample) result).getValue(), matchesPattern("[A-Z]{2}\\d{3}"));
    }

    // --- circular references ---

    @Test
    public void shouldTerminateOnCircularRef() {
        // node -> { self: $ref node } must not StackOverflow; it terminates and yields an object
        ObjectSchema node = new ObjectSchema();
        Schema<?> selfRef = new Schema<>();
        selfRef.set$ref("#/components/schemas/node");
        node.addProperty("self", selfRef);
        Map<String, Schema> definitions = new HashMap<>();
        definitions.put("node", node);

        Example result = fromSchema(node, definitions);

        // terminates without StackOverflow (the forward/circular ref is logged and dropped)
        assertThat(result, is(instanceOf(ObjectExample.class)));
    }

    // --- JSON-Schema constraints (minItems/maxItems, pattern, exclusive bounds, time, minProperties) ---

    @Test
    public void shouldEmitMinItemsArrayItems() {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        schema.setMinItems(3);
        Example result = build(schema);
        assertThat(result, is(instanceOf(ArrayExample.class)));
        assertThat(((ArrayExample) result).getItems(), hasSize(3));
    }

    @Test
    public void shouldClampLargeMinItemsToCap() {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        schema.setMinItems(1000);
        Example result = build(schema);
        // clamped to the small cap so the example does not explode in size
        assertThat(((ArrayExample) result).getItems(), hasSize(5));
    }

    @Test
    public void shouldHonourMaxItemsBelowOne() {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new StringSchema());
        schema.setMaxItems(0);
        Example result = build(schema);
        // maxItems 0 => an empty array, handled gracefully (no items, not a single default item)
        assertThat(((ArrayExample) result).getItems(), is(anyOf(nullValue(), empty())));
    }

    @Test
    public void shouldGenerateStringMatchingPattern() {
        StringSchema schema = new StringSchema();
        schema.setPattern("[A-Z]{3}-\\d{4}");
        Example result = build(schema);
        String value = ((StringExample) result).getValue();
        assertThat(value, matchesPattern("[A-Z]{3}-\\d{4}"));
    }

    @Test
    public void shouldFallBackWhenPatternCannotBeGenerated() {
        StringSchema schema = new StringSchema();
        // an invalid/unsupported regex must not fail generation — fall back to the default sample
        schema.setPattern("[unterminated");
        Example result = build(schema);
        assertThat(((StringExample) result).getValue(), is(SAMPLE_STRING_PROPERTY_VALUE));
    }

    @Test
    public void shouldGenerateValidTimeFormatString() {
        StringSchema schema = new StringSchema();
        schema.setFormat("time");
        Example result = build(schema);
        assertThat(((StringExample) result).getValue(), matchesPattern("\\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void shouldRespectExclusiveMinimumOas30() {
        NumberSchema schema = new NumberSchema();
        schema.setMinimum(new BigDecimal("10"));
        schema.setExclusiveMinimum(true);
        Example result = build(schema);
        assertThat(((DecimalExample) result).getValue(), is(greaterThan(new BigDecimal("10"))));
    }

    @Test
    public void shouldRespectExclusiveMinimumOas31() {
        NumberSchema schema = new NumberSchema();
        schema.setExclusiveMinimumValue(new BigDecimal("10"));
        Example result = build(schema);
        assertThat(((DecimalExample) result).getValue(), is(greaterThan(new BigDecimal("10"))));
    }

    @Test
    public void shouldRespectExclusiveMaximumInteger() {
        IntegerSchema schema = new IntegerSchema();
        schema.setExclusiveMaximumValue(new BigDecimal("5"));
        schema.setMinimum(new BigDecimal("0"));
        Example result = build(schema);
        assertThat(((IntegerExample) result).getValue(), is(lessThan(5)));
    }

    @Test
    public void shouldEmitMinPropertiesForAdditionalProperties() {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(new StringSchema());
        schema.setMinProperties(5);
        Example result = build(schema);
        assertThat(result, is(instanceOf(ObjectExample.class)));
        assertThat(((ObjectExample) result).keySet(), hasSize(5));
    }

    @Test
    public void shouldEmitMinPropertiesUpToCap() {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(new StringSchema());
        schema.setMinProperties(8);
        Example result = build(schema);
        // within the property cap (10), so the constraint is fully satisfied
        assertThat(((ObjectExample) result).keySet(), hasSize(8));
    }

    @Test
    public void shouldRespectExclusiveMinimumInt64WithoutTruncation() {
        IntegerSchema schema = new IntegerSchema();
        schema.setFormat("int64");
        // a bound beyond the int range must not be truncated by the static-mode clamp
        schema.setExclusiveMinimumValue(new BigDecimal("3000000000"));
        Example result = build(schema);
        assertThat(((LongExample) result).getValue(), is(greaterThan(3000000000L)));
    }
}
