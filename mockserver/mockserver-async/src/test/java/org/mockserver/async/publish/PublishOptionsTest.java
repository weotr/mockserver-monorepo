package org.mockserver.async.publish;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link PublishOptions}.
 */
public class PublishOptionsTest {

    @Test
    public void shouldCreateWithAllFields() {
        PublishOptions opts = new PublishOptions("my-key", 2, true);
        assertThat(opts.getKey(), is("my-key"));
        assertThat(opts.getQos(), is(2));
        assertThat(opts.getRetain(), is(true));
        assertThat(opts.isEmpty(), is(false));
    }

    @Test
    public void shouldCreateWithAllNulls() {
        PublishOptions opts = new PublishOptions(null, null, null);
        assertThat(opts.getKey(), is(nullValue()));
        assertThat(opts.getQos(), is(nullValue()));
        assertThat(opts.getRetain(), is(nullValue()));
        assertThat(opts.isEmpty(), is(true));
    }

    @Test
    public void noneShouldBeEmpty() {
        PublishOptions none = PublishOptions.none();
        assertThat(none.isEmpty(), is(true));
        assertThat(none.getKey(), is(nullValue()));
        assertThat(none.getQos(), is(nullValue()));
        assertThat(none.getRetain(), is(nullValue()));
        assertThat(none.getHeaders(), is(anEmptyMap()));
    }

    @Test
    public void shouldNotBeEmptyWithOnlyKey() {
        PublishOptions opts = new PublishOptions("k", null, null);
        assertThat(opts.isEmpty(), is(false));
    }

    @Test
    public void shouldNotBeEmptyWithOnlyQos() {
        PublishOptions opts = new PublishOptions(null, 1, null);
        assertThat(opts.isEmpty(), is(false));
    }

    @Test
    public void shouldNotBeEmptyWithOnlyRetain() {
        PublishOptions opts = new PublishOptions(null, null, false);
        assertThat(opts.isEmpty(), is(false));
    }

    @Test
    public void shouldAcceptQos0() {
        PublishOptions opts = new PublishOptions(null, 0, null);
        assertThat(opts.getQos(), is(0));
    }

    @Test
    public void shouldAcceptQos1() {
        PublishOptions opts = new PublishOptions(null, 1, null);
        assertThat(opts.getQos(), is(1));
    }

    @Test
    public void shouldAcceptQos2() {
        PublishOptions opts = new PublishOptions(null, 2, null);
        assertThat(opts.getQos(), is(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectQos3() {
        new PublishOptions(null, 3, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeQos() {
        new PublishOptions(null, -1, null);
    }

    @Test
    public void toStringShouldContainAllFields() {
        PublishOptions opts = new PublishOptions("k1", 2, true);
        String str = opts.toString();
        assertThat(str, containsString("key=k1"));
        assertThat(str, containsString("qos=2"));
        assertThat(str, containsString("retain=true"));
    }

    // ---- Headers tests ----

    @Test
    public void shouldCreateWithHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("correlationId", "abc-123");
        headers.put("trace-id", "xyz");
        PublishOptions opts = new PublishOptions(null, null, null, headers);
        assertThat(opts.getHeaders(), is(headers));
        assertThat(opts.isEmpty(), is(false));
    }

    @Test
    public void shouldReturnEmptyHeadersWhenNull() {
        PublishOptions opts = new PublishOptions(null, null, null, null);
        assertThat(opts.getHeaders(), is(anEmptyMap()));
        assertThat(opts.isEmpty(), is(true));
    }

    @Test
    public void shouldReturnEmptyHeadersWhenEmptyMap() {
        PublishOptions opts = new PublishOptions(null, null, null, Map.of());
        assertThat(opts.getHeaders(), is(anEmptyMap()));
        assertThat(opts.isEmpty(), is(true));
    }

    @Test
    public void shouldNotBeEmptyWithOnlyHeaders() {
        Map<String, String> headers = Map.of("h1", "v1");
        PublishOptions opts = new PublishOptions(null, null, null, headers);
        assertThat(opts.isEmpty(), is(false));
    }

    @Test
    public void headersShouldBeDefensivelyCopied() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");
        PublishOptions opts = new PublishOptions(null, null, null, headers);
        // Mutate the original map
        headers.put("extra", "should-not-appear");
        assertThat(opts.getHeaders().size(), is(1));
        assertThat(opts.getHeaders().get("key"), is("value"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void headersShouldBeUnmodifiable() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");
        PublishOptions opts = new PublishOptions(null, null, null, headers);
        opts.getHeaders().put("new", "should-fail");
    }

    @Test
    public void backwardCompatConstructorShouldHaveEmptyHeaders() {
        PublishOptions opts = new PublishOptions("k", 1, true);
        assertThat(opts.getHeaders(), is(anEmptyMap()));
    }

    @Test
    public void toStringShouldContainHeaders() {
        Map<String, String> headers = Map.of("h1", "v1");
        PublishOptions opts = new PublishOptions(null, null, null, headers);
        assertThat(opts.toString(), containsString("headers="));
        assertThat(opts.toString(), containsString("h1=v1"));
    }
}
