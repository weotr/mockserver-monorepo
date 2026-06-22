package org.mockserver.templates.engine.helpers;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class CsvTemplateHelperTest {

    private final CsvTemplateHelper helper = new CsvTemplateHelper();

    @Test
    public void shouldParseSimpleRows() {
        List<List<String>> rows = helper.parse("a,b,c\n1,2,3");
        assertThat(rows.size(), is(2));
        assertThat(rows.get(0), is(Arrays.asList("a", "b", "c")));
        assertThat(rows.get(1), is(Arrays.asList("1", "2", "3")));
    }

    @Test
    public void shouldParseCrLfLineEndings() {
        List<List<String>> rows = helper.parse("a,b\r\nc,d");
        assertThat(rows.size(), is(2));
        assertThat(rows.get(0), is(Arrays.asList("a", "b")));
        assertThat(rows.get(1), is(Arrays.asList("c", "d")));
    }

    @Test
    public void shouldParseQuotedFieldWithComma() {
        List<List<String>> rows = helper.parse("\"hello, world\",second");
        assertThat(rows.size(), is(1));
        assertThat(rows.get(0), is(Arrays.asList("hello, world", "second")));
    }

    @Test
    public void shouldParseQuotedFieldWithDoubledQuote() {
        List<List<String>> rows = helper.parse("\"she said \"\"hi\"\"\",next");
        assertThat(rows.get(0), is(Arrays.asList("she said \"hi\"", "next")));
    }

    @Test
    public void shouldParseQuotedFieldWithNewline() {
        List<List<String>> rows = helper.parse("\"line1\nline2\",x");
        assertThat(rows.size(), is(1));
        assertThat(rows.get(0), is(Arrays.asList("line1\nline2", "x")));
    }

    @Test
    public void shouldNotEmitSpuriousRowForTrailingNewline() {
        assertThat(helper.parse("a,b\n"), is(Collections.singletonList(Arrays.asList("a", "b"))));
        assertThat(helper.parse("a,b\r\n"), is(Collections.singletonList(Arrays.asList("a", "b"))));
        List<List<String>> rows = helper.parse("a,b\n1,2\n");
        assertThat(rows.size(), is(2));
        assertThat(rows.get(1), is(Arrays.asList("1", "2")));
    }

    @Test
    public void shouldPreserveTrailingEmptyFieldBeforeNewline() {
        // a line ending in a comma has a genuine trailing empty field
        assertThat(helper.parse("a,\n"), is(Collections.singletonList(Arrays.asList("a", ""))));
    }

    @Test
    public void shouldParseNullOrEmptyAsEmptyList() {
        assertThat(helper.parse(null), is(empty()));
        assertThat(helper.parse(""), is(empty()));
    }

    @Test
    public void shouldFormatRow() {
        assertThat(helper.row(Arrays.asList("a", "b", "c")), is("a,b,c"));
    }

    @Test
    public void shouldFormatRowQuotingWhenNeeded() {
        assertThat(helper.row(Arrays.asList("hello, world", "plain")), is("\"hello, world\",plain"));
        assertThat(helper.row(Arrays.asList("she said \"hi\"")), is("\"she said \"\"hi\"\"\""));
        assertThat(helper.row(Arrays.asList("line1\nline2")), is("\"line1\nline2\""));
    }

    @Test
    public void shouldFormatRowWithNullField() {
        assertThat(helper.row(Arrays.asList("a", null, "c")), is("a,,c"));
        assertThat(helper.row(null), is(""));
        assertThat(helper.row(Collections.emptyList()), is(""));
    }

    @Test
    public void shouldRoundTrip() {
        List<String> fields = Arrays.asList("plain", "with, comma", "with \"quote\"");
        List<List<String>> parsed = helper.parse(helper.row(fields));
        assertThat(parsed.get(0), is(fields));
    }

    @Test
    public void shouldBeRegisteredInTemplateFunctions() {
        Object csvHelper = org.mockserver.templates.engine.TemplateFunctions.BUILT_IN_HELPERS.get("csv");
        assertThat(csvHelper, is(notNullValue()));
        assertThat(csvHelper, instanceOf(CsvTemplateHelper.class));
    }
}
