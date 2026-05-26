package org.mockserver.matchers;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CustomJsonUnitMatcherLoaderTest {

    private String previousProperty;

    @Before
    public void recordPreviousProperty() {
        previousProperty = ConfigurationProperties.customJsonUnitMatchersClass();
        CustomJsonUnitMatcherLoader.reset();
    }

    @After
    public void restorePreviousProperty() {
        ConfigurationProperties.customJsonUnitMatchersClass(previousProperty);
        CustomJsonUnitMatcherLoader.reset();
    }

    @Test
    public void returnsEmptyMapWhenPropertyIsBlank() {
        ConfigurationProperties.customJsonUnitMatchersClass("");

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertTrue(matchers.isEmpty());
    }

    @Test
    public void loadsMatchersFromConfiguredProvider() {
        ConfigurationProperties.customJsonUnitMatchersClass(SingleMatcherProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertEquals(1, matchers.size());
        assertSame(SingleMatcherProvider.LARGER_THAN, matchers.get("largerThan"));
    }

    @Test
    public void cachesProviderResultForSameClassName() {
        ConfigurationProperties.customJsonUnitMatchersClass(CountingProvider.class.getName());
        CountingProvider.callCount = 0;

        CustomJsonUnitMatcherLoader.load();
        CustomJsonUnitMatcherLoader.load();
        CustomJsonUnitMatcherLoader.load();

        assertEquals(1, CountingProvider.callCount);
    }

    @Test
    public void reloadsWhenPropertyChanges() {
        ConfigurationProperties.customJsonUnitMatchersClass(SingleMatcherProvider.class.getName());
        Map<String, Matcher<?>> first = CustomJsonUnitMatcherLoader.load();
        assertSame(SingleMatcherProvider.LARGER_THAN, first.get("largerThan"));

        ConfigurationProperties.customJsonUnitMatchersClass(AlternativeProvider.class.getName());
        Map<String, Matcher<?>> second = CustomJsonUnitMatcherLoader.load();

        assertSame(AlternativeProvider.IS_POSITIVE, second.get("isPositive"));
        assertEquals(1, second.size());
    }

    @Test
    public void returnsEmptyMapWhenClassNotFound() {
        ConfigurationProperties.customJsonUnitMatchersClass("org.mockserver.does.not.Exist");

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertTrue(matchers.isEmpty());
    }

    @Test
    public void returnsEmptyMapWhenClassIsNotProvider() {
        ConfigurationProperties.customJsonUnitMatchersClass(NotAProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertTrue(matchers.isEmpty());
    }

    @Test
    public void returnsEmptyMapWhenProviderConstructorThrows() {
        ConfigurationProperties.customJsonUnitMatchersClass(ThrowingProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertTrue(matchers.isEmpty());
    }

    @Test
    public void returnsEmptyMapWhenProviderReturnsNull() {
        ConfigurationProperties.customJsonUnitMatchersClass(NullReturningProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertTrue(matchers.isEmpty());
    }

    @Test
    public void cachesEmptyMapInstanceWhenPropertyBlank() {
        ConfigurationProperties.customJsonUnitMatchersClass("");

        Map<String, Matcher<?>> first = CustomJsonUnitMatcherLoader.load();
        Map<String, Matcher<?>> second = CustomJsonUnitMatcherLoader.load();

        assertSame(first, second);
    }

    public static class SingleMatcherProvider implements CustomJsonUnitMatcherProvider {

        static final Matcher<?> LARGER_THAN = new LargerThanMatcher(BigDecimal.valueOf(100));

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            Map<String, Matcher<?>> matchers = new HashMap<>();
            matchers.put("largerThan", LARGER_THAN);
            return matchers;
        }
    }

    public static class AlternativeProvider implements CustomJsonUnitMatcherProvider {

        static final Matcher<?> IS_POSITIVE = new LargerThanMatcher(BigDecimal.ZERO);

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            Map<String, Matcher<?>> matchers = new HashMap<>();
            matchers.put("isPositive", IS_POSITIVE);
            return matchers;
        }
    }

    public static class CountingProvider implements CustomJsonUnitMatcherProvider {

        static int callCount;

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            callCount++;
            return Collections.emptyMap();
        }
    }

    public static class NullReturningProvider implements CustomJsonUnitMatcherProvider {

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            return null;
        }
    }

    public static class ThrowingProvider implements CustomJsonUnitMatcherProvider {

        public ThrowingProvider() {
            throw new IllegalStateException("provider boom");
        }

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            return Collections.emptyMap();
        }
    }

    public static class NotAProvider {
    }

    static class LargerThanMatcher extends BaseMatcher<Object> {

        private final BigDecimal threshold;

        LargerThanMatcher(BigDecimal threshold) {
            this.threshold = threshold;
        }

        @Override
        public boolean matches(Object item) {
            if (item == null) {
                return false;
            }
            try {
                return new BigDecimal(item.toString()).compareTo(threshold) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("a number larger than ").appendValue(threshold);
        }
    }
}
