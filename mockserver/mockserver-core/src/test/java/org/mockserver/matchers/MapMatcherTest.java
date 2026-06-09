package org.mockserver.matchers;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Headers;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class MapMatcherTest {

    private Headers matcher;
    private Headers matched;

    @Before
    public void setupTestFixture() {
        matcher = new Headers();
        matched = new Headers();
    }

    @Test
    public void matchesMatchingValues() {
        // given
        matcher.withEntry("foo", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "bar");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void doesNotMatchEmptyValueInExpectation() {
        // given
        matcher.withEntry("foo", "");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "bar", "bob");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexValue() {
        // given
        matcher.withEntry("foo", "b.*");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "bar", "bob");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexKey() {
        // given
        matcher.withEntry("f.*", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "bar");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexValueAndKey() {
        // given
        matcher.withEntry("f.*", "b.*");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "bar");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingValuesWithExtraValues() {
        // given
        matcher.withEntry("foo1", "bar1");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingValuesIgnoringCase() {
        // given
        matcher.withEntry("foo1", "bar1");
        matcher.withEntry("FOO2", "bar2");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("FOO1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexValuesWithExtraValues() {
        // given
        matcher.withEntry("foo1", ".*1");
        matcher.withEntry("foo2", ".*2");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexKeysWithExtraValues() {
        // given
        matcher.withEntry("f.*1", "bar1");
        matcher.withEntry("f.*2", "bar2");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexKeysAndValuesWithExtraValues() {
        // given
        matcher.withEntry("f.*1", ".*1");
        matcher.withEntry("f.*2", ".*2");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesMatchingRegexValuesIgnoringCase() {
        // given
        matcher.withEntry("FOO1", ".*1");
        matcher.withEntry("foo2", ".*2");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo1", "bar1");
        matched.withEntry("FOO2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void matchesEmptyExpectation() {
        // given
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // then
        assertThat(mapMatcher.matches(null, matched), is(true));
    }

    @Test
    public void doesNotMatchDifferentKeys() {
        // given
        matcher.withEntry("foo", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo2", "bar");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void doesNotMatchDifferentValues() {
        // given
        matcher.withEntry("foo", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void doesNotMatchDifferentEmptyValueForControlPlane() {
        // given
        matcher.withEntry("foo", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void doesNotMatchDifferentEmptyValue() {
        // given
        matcher.withEntry("foo", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, false);

        // when
        matched.withEntry("foo", "");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void doesNotMatchIncorrectRegexValue() {
        // given
        matcher.withEntry("foo1", "a.*1");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void doesNotMatchIncorrectRegexKey() {
        // given
        matcher.withEntry("g.*1", "bar1");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void doesNotMatchIncorrectRegexKeyAndValue() {
        // given
        matcher.withEntry("g.*1", "a.*1");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo0", "bar0");
        matched.withEntry("foo1", "bar1");
        matched.withEntry("foo2", "bar2");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexValuePattern() {
        // given
        matcher.withEntry("foo", "/{}");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "/{}/");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }

    @Test
    public void shouldHandleIllegalRegexKeyPattern() {
        // given
        matcher.withEntry("/{}", "bar");
        MultiValueMapMatcher mapMatcher = new MultiValueMapMatcher(new MockServerLogger(), matcher, true);

        // when
        matched.withEntry("foo", "/{}/");

        // then
        assertThat(mapMatcher.matches(null, matched), is(false));
    }
}
