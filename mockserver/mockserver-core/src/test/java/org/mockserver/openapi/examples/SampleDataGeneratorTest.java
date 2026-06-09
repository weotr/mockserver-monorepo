package org.mockserver.openapi.examples;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Tests for {@link SampleDataGenerator}: format compliance, constraint handling, and determinism.
 */
public class SampleDataGeneratorTest {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    private static final Pattern IPV6_PATTERN = Pattern.compile("^[0-9a-fA-F:]+$");

    // --- Determinism ---

    @Test
    public void shouldProduceDeterministicOutputWithSameSeed() {
        SampleDataGenerator gen1 = new SampleDataGenerator(42L);
        SampleDataGenerator gen2 = new SampleDataGenerator(42L);

        assertThat(gen1.email(), is(gen2.email()));
        assertThat(gen1.uuid(), is(gen2.uuid()));
        assertThat(gen1.dateString(), is(gen2.dateString()));
        assertThat(gen1.dateTimeString(), is(gen2.dateTimeString()));
        assertThat(gen1.string(), is(gen2.string()));
        assertThat(gen1.password(), is(gen2.password()));
        assertThat(gen1.byteString(), is(gen2.byteString()));
        assertThat(gen1.ipv4(), is(gen2.ipv4()));
        assertThat(gen1.ipv6(), is(gen2.ipv6()));
        assertThat(gen1.hostname(), is(gen2.hostname()));
        assertThat(gen1.uri(), is(gen2.uri()));
        assertThat(gen1.booleanValue(), is(gen2.booleanValue()));
        assertThat(gen1.integer(null, null), is(gen2.integer(null, null)));
        assertThat(gen1.longValue(null, null), is(gen2.longValue(null, null)));
        assertThat(gen1.floatValue(null, null), is(gen2.floatValue(null, null)));
        assertThat(gen1.doubleValue(null, null), is(gen2.doubleValue(null, null)));
        assertThat(gen1.decimal(null, null), is(gen2.decimal(null, null)));
    }

    @Test
    public void shouldProduceDifferentOutputWithDifferentSeed() {
        SampleDataGenerator gen1 = new SampleDataGenerator(1L);
        SampleDataGenerator gen2 = new SampleDataGenerator(999L);

        // At least some outputs should differ (email is highly likely to differ)
        assertThat(gen1.email(), is(not(gen2.email())));
    }

    // --- Format compliance ---

    @Test
    public void shouldGenerateValidEmail() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String email = gen.email();
        assertTrue("Email should match pattern: " + email, EMAIL_PATTERN.matcher(email).matches());
    }

    @Test
    public void shouldGenerateValidUUID() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String uuid = gen.uuid();
        assertTrue("UUID should match v4 pattern: " + uuid, UUID_PATTERN.matcher(uuid).matches());
    }

    @Test
    public void shouldGenerateValidDate() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String date = gen.dateString();
        // Should parse without exception
        LocalDate parsed = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
        assertNotNull(parsed);
    }

    @Test
    public void shouldGenerateValidDateTime() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String dateTime = gen.dateTimeString();
        // Should parse without exception
        OffsetDateTime parsed = OffsetDateTime.parse(dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertNotNull(parsed);
    }

    @Test
    public void shouldGenerateValidIpv4() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String ipv4 = gen.ipv4();
        assertTrue("IPv4 should match pattern: " + ipv4, IPV4_PATTERN.matcher(ipv4).matches());
    }

    @Test
    public void shouldGenerateValidIpv6() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String ipv6 = gen.ipv6();
        assertTrue("IPv6 should match pattern: " + ipv6, IPV6_PATTERN.matcher(ipv6).matches());
    }

    @Test
    public void shouldGenerateValidBase64Byte() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String byteStr = gen.byteString();
        // Should decode without exception
        byte[] decoded = Base64.getDecoder().decode(byteStr);
        assertThat(decoded.length, greaterThan(0));
    }

    @Test
    public void shouldGenerateValidUri() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String uri = gen.uri();
        assertThat(uri, is(notNullValue()));
        assertThat(uri.length(), greaterThan(0));
    }

    @Test
    public void shouldGenerateValidHostname() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String hostname = gen.hostname();
        assertThat(hostname, is(notNullValue()));
        assertThat(hostname, containsString("."));
    }

    @Test
    public void shouldGenerateNonEmptyPassword() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String password = gen.password();
        assertThat(password, is(notNullValue()));
        assertThat(password.length(), greaterThanOrEqualTo(8));
    }

    // --- Constraint handling ---

    @Test
    public void shouldRespectIntegerMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        int value = gen.integer(BigDecimal.valueOf(10), BigDecimal.valueOf(20));
        assertThat(value, is(both(greaterThanOrEqualTo(10)).and(lessThanOrEqualTo(20))));
    }

    @Test
    public void shouldRespectLongMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        long value = gen.longValue(BigDecimal.valueOf(100), BigDecimal.valueOf(200));
        assertThat(value, is(both(greaterThanOrEqualTo(100L)).and(lessThanOrEqualTo(200L))));
    }

    @Test
    public void shouldRespectFloatMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        float value = gen.floatValue(BigDecimal.valueOf(1.0), BigDecimal.valueOf(5.0));
        assertThat((double) value, is(both(greaterThanOrEqualTo(1.0)).and(lessThanOrEqualTo(5.0))));
    }

    @Test
    public void shouldRespectDoubleMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        double value = gen.doubleValue(BigDecimal.valueOf(10.0), BigDecimal.valueOf(50.0));
        assertThat(value, is(both(greaterThanOrEqualTo(10.0)).and(lessThanOrEqualTo(50.0))));
    }

    @Test
    public void shouldRespectDecimalMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        BigDecimal value = gen.decimal(BigDecimal.valueOf(5.0), BigDecimal.valueOf(10.0));
        assertThat(value.doubleValue(), is(both(greaterThanOrEqualTo(5.0)).and(lessThanOrEqualTo(10.0))));
    }

    @Test
    public void shouldHandleNullMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        // Should not throw
        int intVal = gen.integer(null, null);
        long longVal = gen.longValue(null, null);
        float floatVal = gen.floatValue(null, null);
        double doubleVal = gen.doubleValue(null, null);
        BigDecimal decVal = gen.decimal(null, null);

        assertThat(intVal, is(notNullValue()));
        assertThat(longVal, is(notNullValue()));
        assertThat(decVal, is(notNullValue()));
    }

    @Test
    public void shouldRespectStringMinLength() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringWithConstraints(10, 20);
        assertThat(value.length(), is(both(greaterThanOrEqualTo(10)).and(lessThanOrEqualTo(20))));
    }

    @Test
    public void shouldRespectStringMaxLength() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringWithConstraints(null, 5);
        assertThat(value.length(), is(both(greaterThanOrEqualTo(1)).and(lessThanOrEqualTo(5))));
    }

    @Test
    public void shouldHandleEqualMinMaxForInteger() {
        SampleDataGenerator gen = new SampleDataGenerator();
        int value = gen.integer(BigDecimal.valueOf(42), BigDecimal.valueOf(42));
        assertThat(value, is(42));
    }
}
