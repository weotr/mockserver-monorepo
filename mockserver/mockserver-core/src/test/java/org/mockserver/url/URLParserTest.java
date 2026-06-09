package org.mockserver.url;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class URLParserTest {

    @Test
    public void shouldDetectPath() {
        // isn't path
        assertThat(URLParser.isFullUrl("http://www.mock-server.com/some/path"), is(true));
        assertThat(URLParser.isFullUrl("https://www.mock-server.com/some/path"), is(true));
        assertThat(URLParser.isFullUrl("https:////localhost/some/path"), is(true));

        // is path
        assertThat(URLParser.isFullUrl(null), is(false));
        assertThat(URLParser.isFullUrl("/some/path"), is(false));
        assertThat(URLParser.isFullUrl("some/path"), is(false));
    }

    @Test
    public void shouldReturnPath() {
        assertThat(URLParser.returnPath("http://www.mock-server.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.mock-server.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc123.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com//some/path"), is("//some/path"));
        assertThat(URLParser.returnPath("http://Administrator:password@192.168.50.70:8091/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("https://Administrator:password@www.abc.123.com/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("/some/path"), is("/some/path"));
        assertThat(URLParser.returnPath("//some/path"), is("//some/path"));
        assertThat(URLParser.returnPath("/123/456"), is("/123/456"));
    }

    @Test
    public void shouldStripQueryString() {
        assertThat(URLParser.returnPath("http://www.mock-server.com/some/path?foo=bar"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.mock-server.com/some/path?foo=bar&bar=foo"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc123.com/some/path?foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com/some/path?foo=bar"), is("/some/path"));
        assertThat(URLParser.returnPath("https://www.abc.123.com/some/path?foo=bar&bar=foo&bar=foo"), is("/some/path"));
        assertThat(URLParser.returnPath("http://Administrator:password@192.168.50.70:8091/some/path?foo=bar&bar=foo"), is("/some/path"));
        assertThat(URLParser.returnPath("https://Administrator:password@www.abc.123.com/some/path?foo=bar"), is("/some/path"));
        assertThat(URLParser.returnPath("https://Administrator:password@www.abc.123.com/some/path?foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo&foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/some/path"));
        assertThat(URLParser.returnPath("/some/path?foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo&foo=foo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/some/path"));
        assertThat(URLParser.returnPath("/123/456%3Ffoo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"), is("/123/456%3Ffoo%3Dbar%26bar%3Dfoo%26bar%3Dfoo"));
    }


}
