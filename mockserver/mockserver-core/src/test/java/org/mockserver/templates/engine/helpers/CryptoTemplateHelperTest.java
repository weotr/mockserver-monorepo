package org.mockserver.templates.engine.helpers;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class CryptoTemplateHelperTest {

    private final CryptoTemplateHelper helper = new CryptoTemplateHelper();

    @Test
    public void shouldComputeMd5() {
        assertThat(helper.md5("abc"), is("900150983cd24fb0d6963f7d28e17f72"));
        assertThat(helper.md5(null), is(""));
    }

    @Test
    public void shouldComputeSha1() {
        assertThat(helper.sha1("abc"), is("a9993e364706816aba3e25717850c26c9cd0d89d"));
        assertThat(helper.sha1(null), is(""));
    }

    @Test
    public void shouldComputeSha256() {
        assertThat(helper.sha256("abc"), is("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        assertThat(helper.sha256(null), is(""));
    }

    @Test
    public void shouldComputeSha512() {
        assertThat(helper.sha512("abc"), is("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"));
        assertThat(helper.sha512(null), is(""));
    }

    @Test
    public void shouldComputeHmacSha256() {
        assertThat(helper.hmacSha256("key", "message"), is("6e9ef29b75fffc5b7abae527d58fdadb2fe42e7219011976917343065f58ed4a"));
        assertThat(helper.hmacSha256(null, "message"), is(""));
        assertThat(helper.hmacSha256("key", null), is(""));
    }

    @Test
    public void shouldReturnLowercaseHex() {
        String result = helper.sha256("MockServer");
        assertThat(result, matchesPattern("[0-9a-f]{64}"));
    }

    @Test
    public void shouldBeRegisteredInTemplateFunctions() {
        Object cryptoHelper = org.mockserver.templates.engine.TemplateFunctions.BUILT_IN_HELPERS.get("crypto");
        assertThat(cryptoHelper, is(notNullValue()));
        assertThat(cryptoHelper, instanceOf(CryptoTemplateHelper.class));
    }
}
