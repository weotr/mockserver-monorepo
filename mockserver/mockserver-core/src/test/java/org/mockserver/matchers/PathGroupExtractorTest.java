package org.mockserver.matchers;

import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class PathGroupExtractorTest {

    @Test
    public void shouldExtractNumberedGroupsOneBasedWithWholeMatchAtZero() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(\\d+)/orders/(\\w+)", "/users/42/orders/abc", false);

        // then
        assertThat(groups.hasGroups(), is(true));
        assertThat(groups.getNumberedGroups(), is(Arrays.asList("/users/42/orders/abc", "42", "abc")));
        assertThat(groups.getNamedGroups().isEmpty(), is(true));
    }

    @Test
    public void shouldExtractNamedGroups() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(?<userId>\\d+)/orders/(?<orderId>\\w+)", "/users/42/orders/abc", false);

        // then
        assertThat(groups.hasGroups(), is(true));
        assertThat(groups.getNumberedGroups(), is(Arrays.asList("/users/42/orders/abc", "42", "abc")));
        assertThat(groups.getNamedGroups(), allOf(hasEntry("userId", "42"), hasEntry("orderId", "abc")));
    }

    @Test
    public void shouldReturnNoGroupsForLiteralPathWithNoCapturingGroups() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/all", "/users/all", false);

        // then
        assertThat(groups.hasGroups(), is(false));
        assertThat(groups.getNumberedGroups().isEmpty(), is(true));
        assertThat(groups.getNamedGroups().isEmpty(), is(true));
    }

    @Test
    public void shouldReturnNoGroupsWhenPatternDoesNotMatch() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(\\d+)", "/users/bob", false);

        // then
        assertThat(groups.hasGroups(), is(false));
    }

    @Test
    public void shouldAnchorFullMatch() {
        // a group present but only a substring match must not extract (NottableString matches anchored)
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(\\d+)", "/users/42/extra", false);

        // then
        assertThat(groups.hasGroups(), is(false));
    }

    @Test
    public void shouldYieldNullForNonParticipatingOptionalGroup() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(\\d+)(/admin)?", "/users/42", false);

        // then
        assertThat(groups.hasGroups(), is(true));
        assertThat(groups.getNumberedGroups().get(1), is("42"));
        assertThat(groups.getNumberedGroups().get(2), is(nullValue()));
    }

    @Test
    public void shouldReturnNoGroupsForInvalidPatternWithoutThrowing() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(\\d+", "/users/42", false);

        // then
        assertThat(groups.hasGroups(), is(false));
    }

    @Test
    public void shouldReturnNoGroupsForNullOrEmptyInputs() {
        assertThat(PathGroupExtractor.extract(null, "/users/42", false).hasGroups(), is(false));
        assertThat(PathGroupExtractor.extract("", "/users/42", false).hasGroups(), is(false));
        assertThat(PathGroupExtractor.extract("/users/(\\d+)", null, false).hasGroups(), is(false));
    }

    @Test
    public void shouldNotTreatNegativeLookbehindAsNamedGroup() {
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/users/(?<![a-z])(\\d+)", "/users/42", false);

        // then
        assertThat(groups.hasGroups(), is(true));
        assertThat(groups.getNumberedGroups().get(1), is("42"));
        assertThat(groups.getNamedGroups().isEmpty(), is(true));
    }

    @Test
    public void shouldExtractGroupsCaseInsensitivelyByDefaultMatchingThePathMatcher() {
        // the default path matcher is case-insensitive (DOTALL|CASE_INSENSITIVE|UNICODE_CASE), so a literal
        // letter in the pattern that differs only in case from the actual path must still extract its groups
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/User/([a-z]+)", "/user/bob", false);

        // then
        assertThat(groups.hasGroups(), is(true));
        assertThat(groups.getNumberedGroups().get(1), is("bob"));
    }

    @Test
    public void shouldExtractGroupsCaseSensitivelyWhenMatchExactCase() {
        // when matchExactCase is on the path matcher compiles DOTALL only, so a case difference must NOT match
        // when
        PathGroupExtractor.PathGroups caseDiffers = PathGroupExtractor.extract("/User/([a-z]+)", "/user/bob", true);
        PathGroupExtractor.PathGroups caseMatches = PathGroupExtractor.extract("/user/([a-z]+)", "/user/bob", true);

        // then
        assertThat(caseDiffers.hasGroups(), is(false));
        assertThat(caseMatches.hasGroups(), is(true));
        assertThat(caseMatches.getNumberedGroups().get(1), is("bob"));
    }

    @Test
    public void shouldMatchAcrossNewlinesWithDotAll() {
        // both modes compile with DOTALL, so '.' spans a newline exactly as the path matcher does
        // when
        PathGroupExtractor.PathGroups groups = PathGroupExtractor.extract("/a/(.+)", "/a/b\nc", false);

        // then
        assertThat(groups.hasGroups(), is(true));
        assertThat(groups.getNumberedGroups().get(1), is("b\nc"));
    }
}
