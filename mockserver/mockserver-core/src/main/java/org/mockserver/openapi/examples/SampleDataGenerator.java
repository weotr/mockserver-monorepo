package org.mockserver.openapi.examples;

import net.datafaker.Faker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Random;

/**
 * Generates realistic, schema/format-aware example values using Datafaker with a fixed seed
 * for deterministic output. Used by {@link ExampleBuilder} when the
 * {@code generateRealisticExampleValues} configuration flag is enabled.
 */
public class SampleDataGenerator {

    private static final long FIXED_SEED = 42L;

    private final Faker faker;
    private final Random random;

    public SampleDataGenerator() {
        this(FIXED_SEED);
    }

    public SampleDataGenerator(long seed) {
        this.random = new Random(seed);
        this.faker = new Faker(random);
    }

    // --- String formats ---

    public String email() {
        return faker.internet().emailAddress();
    }

    public String uuid() {
        // Use the seeded Random to generate a deterministic UUID
        long mostSig = random.nextLong();
        long leastSig = random.nextLong();
        // Set version 4 and variant bits
        mostSig = (mostSig & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        leastSig = (leastSig & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new java.util.UUID(mostSig, leastSig).toString();
    }

    public String dateString() {
        LocalDate date = LocalDate.of(
            2020 + random.nextInt(5),
            1 + random.nextInt(12),
            1 + random.nextInt(28)
        );
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public String dateTimeString() {
        LocalDate date = LocalDate.of(
            2020 + random.nextInt(5),
            1 + random.nextInt(12),
            1 + random.nextInt(28)
        );
        OffsetDateTime dateTime = date.atTime(
            random.nextInt(24),
            random.nextInt(60),
            random.nextInt(60)
        ).atOffset(ZoneOffset.UTC);
        return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public String uri() {
        return faker.internet().url();
    }

    public String hostname() {
        return faker.internet().domainName();
    }

    public String ipv4() {
        return faker.internet().ipV4Address();
    }

    public String ipv6() {
        return faker.internet().ipV6Address();
    }

    public String byteString() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public String password() {
        return faker.internet().password(8, 16, true, true, true);
    }

    public String string() {
        return faker.lorem().word();
    }

    public String stringWithConstraints(Integer minLength, Integer maxLength) {
        int min = minLength != null ? minLength : 1;
        int max = maxLength != null ? maxLength : Math.max(min, 20);
        if (max < min) {
            max = min;
        }
        int targetLength = min + (max > min ? random.nextInt(max - min + 1) : 0);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < targetLength) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(faker.lorem().word());
        }
        String result = sb.toString();
        if (result.length() > max) {
            result = result.substring(0, max);
        }
        if (result.length() < min) {
            // pad with characters if the word generator didn't produce enough
            while (result.length() < min) {
                result = result + "a";
            }
        }
        return result;
    }

    // --- Numeric types ---

    public int integer(BigDecimal minimum, BigDecimal maximum) {
        int min = minimum != null ? minimum.intValue() : 0;
        int max = maximum != null ? maximum.intValue() : 1000;
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    public long longValue(BigDecimal minimum, BigDecimal maximum) {
        long min = minimum != null ? minimum.longValue() : 0L;
        long max = maximum != null ? maximum.longValue() : 10000L;
        if (max <= min) {
            return min;
        }
        return min + ((long) (random.nextDouble() * (max - min + 1)));
    }

    public float floatValue(BigDecimal minimum, BigDecimal maximum) {
        float min = minimum != null ? minimum.floatValue() : 0.0f;
        float max = maximum != null ? maximum.floatValue() : 100.0f;
        if (max <= min) {
            return min;
        }
        float value = min + random.nextFloat() * (max - min);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    public double doubleValue(BigDecimal minimum, BigDecimal maximum) {
        double min = minimum != null ? minimum.doubleValue() : 0.0;
        double max = maximum != null ? maximum.doubleValue() : 1000.0;
        if (max <= min) {
            return min;
        }
        double value = min + random.nextDouble() * (max - min);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public BigDecimal decimal(BigDecimal minimum, BigDecimal maximum) {
        double min = minimum != null ? minimum.doubleValue() : 0.0;
        double max = maximum != null ? maximum.doubleValue() : 1000.0;
        if (max <= min) {
            return BigDecimal.valueOf(min).setScale(2, RoundingMode.HALF_UP);
        }
        double value = min + random.nextDouble() * (max - min);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    // --- Boolean ---

    public boolean booleanValue() {
        return random.nextBoolean();
    }
}
