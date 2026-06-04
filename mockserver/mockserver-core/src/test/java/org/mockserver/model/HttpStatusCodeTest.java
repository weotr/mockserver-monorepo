package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.Matchers.nullValue;
/**
 * @author jamesdbloom
 */
public class HttpStatusCodeTest {

    @Test
    public void shouldFindEnumForCode() {
        assertThat(HttpStatusCode.code(302), is(HttpStatusCode.FOUND_302));
        assertThat(HttpStatusCode.code(502), is(HttpStatusCode.BAD_GATEWAY_502));
        assertThat(HttpStatusCode.code(507), is(HttpStatusCode.INSUFFICIENT_STORAGE_507));
        assertThat(HttpStatusCode.code(600), nullValue());
    }

    @Test
    public void shouldReturnCorrectValues() {
        assertThat("Moved Temporarily", is(HttpStatusCode.FOUND_302.reasonPhrase()));
        assertThat(302, is(HttpStatusCode.FOUND_302.code()));
    }
}
