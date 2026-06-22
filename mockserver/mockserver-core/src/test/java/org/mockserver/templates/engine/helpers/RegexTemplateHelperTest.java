package org.mockserver.templates.engine.helpers;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class RegexTemplateHelperTest {

    private final RegexTemplateHelper helper = new RegexTemplateHelper();

    @Test
    public void shouldMatch() {
        assertThat(helper.matches("hello world", "wor.d"), is(true));
        assertThat(helper.matches("hello world", "^world$"), is(false));
        assertThat(helper.matches(null, "x"), is(false));
        assertThat(helper.matches("x", null), is(false));
    }

    @Test
    public void shouldReplaceAll() {
        assertThat(helper.replaceAll("a1b2c3", "[0-9]", "-"), is("a-b-c-"));
        assertThat(helper.replaceAll("hello", "l", ""), is("heo"));
        assertThat(helper.replaceAll(null, "x", "y"), is(""));
        assertThat(helper.replaceAll("keep", null, "y"), is("keep"));
    }

    @Test
    public void shouldReplaceAllWithBackreference() {
        assertThat(helper.replaceAll("2026-06-16", "([0-9]{4})-([0-9]{2})-([0-9]{2})", "$3/$2/$1"), is("16/06/2026"));
    }

    @Test
    public void shouldExtractGroup() {
        assertThat(helper.group("order-12345", "order-([0-9]+)", 1), is("12345"));
        assertThat(helper.group("user@example.com", "([^@]+)@(.+)", 2), is("example.com"));
    }

    @Test
    public void shouldReturnWholeMatchForGroupZero() {
        assertThat(helper.group("abc123def", "[0-9]+", 0), is("123"));
    }

    @Test
    public void shouldReturnEmptyWhenNoMatch() {
        assertThat(helper.group("abc", "([0-9]+)", 1), is(""));
        assertThat(helper.group(null, "x", 1), is(""));
        assertThat(helper.group("x", null, 1), is(""));
    }

    @Test
    public void shouldBeRegisteredInTemplateFunctions() {
        Object regexHelper = org.mockserver.templates.engine.TemplateFunctions.BUILT_IN_HELPERS.get("regex");
        assertThat(regexHelper, is(notNullValue()));
        assertThat(regexHelper, instanceOf(RegexTemplateHelper.class));
    }
}
