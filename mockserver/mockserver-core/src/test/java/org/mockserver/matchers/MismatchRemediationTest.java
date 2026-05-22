package org.mockserver.matchers;

import org.junit.Test;

import java.util.*;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Unit tests for {@link MismatchRemediation} heuristics.
 */
public class MismatchRemediationTest {

    // --- method hints ---

    @Test
    public void shouldHintMethodMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected 'POST' but was 'GET'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.METHOD, diffs);

        // then
        assertThat(hint, is("use method POST not GET"));
    }

    @Test
    public void shouldHintMethodWhenNoExpectedButWas() {
        // given
        List<String> diffs = Collections.singletonList("method did not match for DELETE request");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.METHOD, diffs);

        // then
        assertThat(hint, containsString("DELETE"));
    }

    // --- path hints ---

    @Test
    public void shouldHintTrailingSlashMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected '/api/users/' but was '/api/users'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.PATH, diffs);

        // then
        assertThat(hint, is("add trailing slash: send /api/users/ not /api/users"));
    }

    @Test
    public void shouldHintRemoveTrailingSlash() {
        // given
        List<String> diffs = Collections.singletonList("expected '/api/users' but was '/api/users/'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.PATH, diffs);

        // then
        assertThat(hint, is("remove trailing slash: send /api/users not /api/users/"));
    }

    @Test
    public void shouldHintPathMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected '/api/v2/users' but was '/api/v1/users'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.PATH, diffs);

        // then
        assertThat(hint, is("use path /api/v2/users not /api/v1/users"));
    }

    @Test
    public void shouldHintGenericPathCheck() {
        // given
        List<String> diffs = Collections.singletonList("some unrecognised difference format");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.PATH, diffs);

        // then
        assertThat(hint, is("check the request path"));
    }

    // --- header hints ---

    @Test
    public void shouldHintHeaderCaseMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected 'Content-Type' but was 'content-type'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.HEADERS, diffs);

        // then
        assertThat(hint, is("header name case mismatch: send Content-Type not content-type"));
    }

    @Test
    public void shouldHintMissingHeader() {
        // given
        List<String> diffs = Collections.singletonList("multimap does not contain entry for \"Authorization\"");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.HEADERS, diffs);

        // then
        assertThat(hint, is("add missing header Authorization"));
    }

    @Test
    public void shouldHintContentTypeHeader() {
        // given
        List<String> diffs = Collections.singletonList("content-type value was different");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.HEADERS, diffs);

        // then
        assertThat(hint, is("check the Content-Type header value"));
    }

    @Test
    public void shouldHintGenericHeaderCheck() {
        // given
        List<String> diffs = Collections.singletonList("something else");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.HEADERS, diffs);

        // then
        assertThat(hint, is("check request headers"));
    }

    // --- query parameter hints ---

    @Test
    public void shouldHintMissingQueryParam() {
        // given
        List<String> diffs = Collections.singletonList("multimap does not contain entry for \"page\"");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.QUERY_PARAMETERS, diffs);

        // then
        assertThat(hint, is("add missing query parameter page"));
    }

    @Test
    public void shouldHintQueryParamCaseMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected 'PageSize' but was 'pageSize'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.QUERY_PARAMETERS, diffs);

        // then
        assertThat(hint, is("query parameter name case mismatch: send PageSize not pageSize"));
    }

    // --- cookie hints ---

    @Test
    public void shouldHintMissingCookie() {
        // given
        List<String> diffs = Collections.singletonList("multimap does not contain entry for \"sessionId\"");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.COOKIES, diffs);

        // then
        assertThat(hint, is("add missing cookie sessionId"));
    }

    @Test
    public void shouldHintCookieCaseMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected 'SessionId' but was 'sessionid'");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.COOKIES, diffs);

        // then
        assertThat(hint, is("cookie name case mismatch: send SessionId not sessionid"));
    }

    // --- body hints ---

    @Test
    public void shouldHintBodyJsonSchemaMismatch() {
        // given
        List<String> diffs = Collections.singletonList("body did not match JSON schema validation");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.BODY, diffs);

        // then
        assertThat(hint, is("request body does not match the expected JSON schema"));
    }

    @Test
    public void shouldHintBodyContentTypeMismatch() {
        // given
        List<String> diffs = Collections.singletonList("expected content-type to be application/json");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.BODY, diffs);

        // then
        assertThat(hint, is("check the Content-Type header and body format"));
    }

    @Test
    public void shouldHintGenericBodyCheck() {
        // given
        List<String> diffs = Collections.singletonList("body value was different");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.BODY, diffs);

        // then
        assertThat(hint, is("check the request body content"));
    }

    // --- other fields ---

    @Test
    public void shouldHintSecureField() {
        // given
        List<String> diffs = Collections.singletonList("expected secure but was not");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.SECURE, diffs);

        // then
        assertThat(hint, is("check whether the request uses HTTPS vs HTTP"));
    }

    @Test
    public void shouldHintPathParameters() {
        // given
        List<String> diffs = Collections.singletonList("path parameter mismatch");

        // when
        String hint = MismatchRemediation.hint(MatchDifference.Field.PATH_PARAMETERS, diffs);

        // then
        assertThat(hint, is("check path parameter values"));
    }

    // --- edge cases ---

    @Test
    public void shouldReturnEmptyForNullField() {
        // given / when
        String hint = MismatchRemediation.hint(null, Collections.singletonList("something"));

        // then
        assertThat(hint, is(""));
    }

    @Test
    public void shouldReturnEmptyForNullDifferences() {
        // given / when
        String hint = MismatchRemediation.hint(MatchDifference.Field.METHOD, null);

        // then
        assertThat(hint, is(""));
    }

    @Test
    public void shouldReturnEmptyForEmptyDifferences() {
        // given / when
        String hint = MismatchRemediation.hint(MatchDifference.Field.METHOD, Collections.emptyList());

        // then
        assertThat(hint, is(""));
    }

    // --- allHints ---

    @Test
    public void shouldProduceAllHints() {
        // given
        Map<MatchDifference.Field, List<String>> diffs = new LinkedHashMap<>();
        diffs.put(MatchDifference.Field.METHOD, Collections.singletonList("expected 'POST' but was 'GET'"));
        diffs.put(MatchDifference.Field.PATH, Collections.singletonList("expected '/api/users/' but was '/api/users'"));

        // when
        Map<MatchDifference.Field, String> hints = MismatchRemediation.allHints(diffs);

        // then
        assertThat(hints.size(), is(2));
        assertThat(hints.get(MatchDifference.Field.METHOD), is("use method POST not GET"));
        assertThat(hints.get(MatchDifference.Field.PATH), containsString("trailing slash"));
    }

    @Test
    public void shouldReturnEmptyHintsForNullDifferences() {
        // given / when
        Map<MatchDifference.Field, String> hints = MismatchRemediation.allHints(null);

        // then
        assertThat(hints.isEmpty(), is(true));
    }

    // --- pathsDifferOnlyByTrailingSlash ---

    @Test
    public void shouldDetectTrailingSlashDifference() {
        assertThat(MismatchRemediation.pathsDifferOnlyByTrailingSlash("/api/users/", "/api/users"), is(true));
        assertThat(MismatchRemediation.pathsDifferOnlyByTrailingSlash("/api/users", "/api/users/"), is(true));
    }

    @Test
    public void shouldNotDetectTrailingSlashWhenPathsDiffer() {
        assertThat(MismatchRemediation.pathsDifferOnlyByTrailingSlash("/api/v1", "/api/v2"), is(false));
    }

    @Test
    public void shouldNotDetectTrailingSlashWhenEqual() {
        assertThat(MismatchRemediation.pathsDifferOnlyByTrailingSlash("/api/users", "/api/users"), is(false));
    }

    @Test
    public void shouldHandleNullPaths() {
        assertThat(MismatchRemediation.pathsDifferOnlyByTrailingSlash(null, "/api"), is(false));
        assertThat(MismatchRemediation.pathsDifferOnlyByTrailingSlash("/api", null), is(false));
    }
}
