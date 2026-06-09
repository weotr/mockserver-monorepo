package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class BinaryMatcherTest {

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8)).matches(null, "some_value".getBytes(UTF_8)), is(true));
    }

    @Test
    public void shouldNotMatchMatchingString() {
        assertThat(notMatcher(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8))).matches(null, "some_value".getBytes(UTF_8)), is(false));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new BinaryMatcher(new MockServerLogger(), null).matches(null, "some_value".getBytes(UTF_8)), is(true));
    }

    @Test
    public void shouldNotMatchNullExpectation() {
        assertThat(notMatcher(new BinaryMatcher(new MockServerLogger(), null)).matches(null, "some_value".getBytes(UTF_8)), is(false));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new BinaryMatcher(new MockServerLogger(), "".getBytes(UTF_8)).matches(null, "some_value".getBytes(UTF_8)), is(true));
    }

    @Test
    public void shouldNotMatchEmptyExpectation() {
        assertThat(notMatcher(new BinaryMatcher(new MockServerLogger(), "".getBytes(UTF_8))).matches(null, "some_value".getBytes(UTF_8)), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectString() {
        assertThat(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8)).matches(null, "not_matching".getBytes(UTF_8)), is(false));
    }

    @Test
    public void shouldMatchIncorrectString() {
        assertThat(notMatcher(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8))).matches(null, "not_matching".getBytes(UTF_8)), is(true));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8)).matches(null, null), is(false));
    }

    @Test
    public void shouldMatchNullTest() {
        assertThat(notMatcher(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8))).matches(null, null), is(true));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8)).matches(null, "".getBytes(UTF_8)), is(false));
    }

    @Test
    public void shouldMatchEmptyTest() {
        assertThat(notMatcher(new BinaryMatcher(new MockServerLogger(), "some_value".getBytes(UTF_8))).matches(null, "".getBytes(UTF_8)), is(true));
    }
}
