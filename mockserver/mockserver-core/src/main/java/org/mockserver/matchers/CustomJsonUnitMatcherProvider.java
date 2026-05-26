package org.mockserver.matchers;

import org.hamcrest.Matcher;

import java.util.Map;

/**
 * Plug-in point for registering custom <a href="https://github.com/lukas-krecan/JsonUnit">json-unit</a>
 * matchers so they can be referenced from JSON body expectations using the
 * {@code ${json-unit.matches:name}} placeholder.
 *
 * <p>An implementation is loaded reflectively by {@link CustomJsonUnitMatcherLoader} when the
 * {@code mockserver.customJsonUnitMatchersClass} configuration property names a fully
 * qualified class. The class must have a public no-arg constructor.</p>
 *
 * <p>The returned {@link Matcher}s are registered against the json-unit
 * {@link net.javacrumbs.jsonunit.core.Configuration} used to match each request body, so the
 * map should be deterministic and inexpensive to produce - it is called once per provider
 * instance and the result is cached for the lifetime of the JVM.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * public class MyMatchers implements CustomJsonUnitMatcherProvider {
 *     public Map<String, Matcher<?>> jsonUnitMatchers() {
 *         Map<String, Matcher<?>> matchers = new HashMap<>();
 *         matchers.put("largerThan", new LargerThanMatcher(BigDecimal.valueOf(100)));
 *         return matchers;
 *     }
 * }
 * }</pre>
 *
 * Then start MockServer with {@code -Dmockserver.customJsonUnitMatchersClass=com.example.MyMatchers}
 * and reference the matcher in a JSON body expectation:
 * <pre>{@code
 * { "price": "${json-unit.matches:largerThan}" }
 * }</pre>
 */
public interface CustomJsonUnitMatcherProvider {

    /**
     * Returns the named matchers to register with json-unit. Keys are the matcher names referenced
     * by the {@code ${json-unit.matches:name}} placeholder. Must not return {@code null}.
     */
    Map<String, Matcher<?>> jsonUnitMatchers();
}
