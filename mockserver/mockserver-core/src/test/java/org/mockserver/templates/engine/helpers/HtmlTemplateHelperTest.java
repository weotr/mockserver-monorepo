package org.mockserver.templates.engine.helpers;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class HtmlTemplateHelperTest {

    private final HtmlTemplateHelper helper = new HtmlTemplateHelper();

    @Test
    public void shouldEscape() {
        assertThat(helper.escape("<a href=\"x\">A & B</a>"), is("&lt;a href=&quot;x&quot;&gt;A &amp; B&lt;/a&gt;"));
        assertThat(helper.escape("plain"), is("plain"));
    }

    @Test
    public void shouldEscapeNullAsEmpty() {
        assertThat(helper.escape(null), is(""));
    }

    @Test
    public void shouldUnescape() {
        assertThat(helper.unescape("&lt;a&gt;A &amp; B&lt;/a&gt;"), is("<a>A & B</a>"));
        assertThat(helper.unescape("plain"), is("plain"));
    }

    @Test
    public void shouldUnescapeNullAsEmpty() {
        assertThat(helper.unescape(null), is(""));
    }

    @Test
    public void shouldRoundTrip() {
        String original = "<tag attr=\"v\">cafe & co © 2026</tag>";
        assertThat(helper.unescape(helper.escape(original)), is(original));
    }

    @Test
    public void shouldBeRegisteredInTemplateFunctions() {
        Object htmlHelper = org.mockserver.templates.engine.TemplateFunctions.BUILT_IN_HELPERS.get("html");
        assertThat(htmlHelper, is(notNullValue()));
        assertThat(htmlHelper, instanceOf(HtmlTemplateHelper.class));
    }
}
