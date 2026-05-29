package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.NormalizationOptions;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.NormalizationOptions.normalizationOptions;

public class PromptNormalizerTest {

    @Test
    public void shouldReturnInputUnchangedWhenOptionsNull() {
        assertThat(PromptNormalizer.normalize("  Hello   World  ", null), is("  Hello   World  "));
    }

    @Test
    public void shouldReturnNullWhenTextNull() {
        assertThat(PromptNormalizer.normalize(null, normalizationOptions()), is((String) null));
    }

    @Test
    public void shouldCollapseWhitespaceByDefault() {
        // default options have collapseWhitespace=true, sortJsonKeys=true (no effect on plain text)
        assertThat(PromptNormalizer.normalize("Hello   \n\t World", normalizationOptions()), is("Hello World"));
    }

    @Test
    public void shouldNotCollapseWhitespaceWhenDisabled() {
        NormalizationOptions options = new NormalizationOptions().withCollapseWhitespace(false);
        assertThat(PromptNormalizer.normalize("Hello   World", options), is("Hello   World"));
    }

    @Test
    public void shouldLowercaseWhenEnabled() {
        NormalizationOptions options = new NormalizationOptions().withLowercase(true);
        assertThat(PromptNormalizer.normalize("Hello WORLD", options), is("hello world"));
    }

    @Test
    public void shouldSortJsonKeysSoOrderingIsIrrelevant() {
        String a = PromptNormalizer.normalize("{\"b\": 1, \"a\": 2}", normalizationOptions());
        String b = PromptNormalizer.normalize("{\"a\": 2, \"b\": 1}", normalizationOptions());
        assertThat(a, is(b));
    }

    @Test
    public void shouldSortNestedJsonKeys() {
        String a = PromptNormalizer.normalize("{\"outer\": {\"y\": 1, \"x\": 2}}", normalizationOptions());
        String b = PromptNormalizer.normalize("{\"outer\": {\"x\": 2, \"y\": 1}}", normalizationOptions());
        assertThat(a, is(b));
    }

    @Test
    public void shouldFallBackToTextNormalisationForNonJson() {
        // not JSON — must not throw, just collapse whitespace
        assertThat(PromptNormalizer.normalize("not  json  here", normalizationOptions()), is("not json here"));
    }

    @Test
    public void shouldDropNamedJsonField() {
        NormalizationOptions options = new NormalizationOptions()
            .withSortJsonKeys(true)
            .withDropVolatileFields(Arrays.asList("requestId"));
        String withId = PromptNormalizer.normalize("{\"requestId\": \"abc\", \"q\": \"hi\"}", options);
        String withoutId = PromptNormalizer.normalize("{\"q\": \"hi\"}", options);
        assertThat(withId, is(withoutId));
        assertThat(withId, not(containsString("requestId")));
    }

    @Test
    public void shouldStripBuiltInVolatileTimestampsUuidsAndIds() {
        NormalizationOptions options = new NormalizationOptions()
            .withCollapseWhitespace(true)
            .withDropBuiltInVolatileFields(true);
        String one = PromptNormalizer.normalize(
            "trace at 2026-05-29T10:15:30Z id 550e8400-e29b-41d4-a716-446655440000 req_abc123def", options);
        String two = PromptNormalizer.normalize(
            "trace at 2024-01-01T00:00:00Z id 11111111-2222-3333-4444-555555555555 req_zzz999yyy", options);
        assertThat(one, is(two));
    }

    @Test
    public void shouldBeIdempotent() {
        NormalizationOptions options = new NormalizationOptions()
            .withCollapseWhitespace(true)
            .withLowercase(true)
            .withSortJsonKeys(true);
        String once = PromptNormalizer.normalize("{\"B\": \"X   Y\", \"a\": 1}", options);
        String twice = PromptNormalizer.normalize(once, options);
        assertThat(twice, is(once));
    }
}
