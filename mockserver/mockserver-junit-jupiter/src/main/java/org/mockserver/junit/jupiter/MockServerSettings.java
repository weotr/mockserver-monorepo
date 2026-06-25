package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Configure MockServer when used in conjunction with {@link MockServerExtension}
 */
@ExtendWith(MockServerExtension.class)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface MockServerSettings {
    boolean perTestSuite() default false;
    int[] ports() default {};

    /**
     * When {@code true} the MockServer is reset (all expectations and recorded
     * requests cleared) before each test method, so expectations set in one test
     * do not bleed into the next when a single server instance is shared across
     * the test class (or across the whole suite via {@link #perTestSuite()}).
     * <p>
     * Defaults to {@code false} to preserve the historic behaviour where the
     * shared server is never reset between tests — leaving expectations set in
     * {@code @BeforeAll} intact for every test in the class.
     * <p>
     * The reset fires in the extension's {@code BeforeEachCallback}, which runs
     * <em>before</em> any user {@code @BeforeEach} methods, so expectations
     * registered in a {@code @BeforeEach} are safe — they are applied after the
     * reset and are therefore present for the test that follows.
     * <p>
     * When combined with {@link #perTestSuite()} {@code = true} the reset clears
     * the shared suite-wide server before each test, so isolation holds even
     * across multiple test classes that share that one server.
     */
    boolean resetBeforeEach() default false;
}
