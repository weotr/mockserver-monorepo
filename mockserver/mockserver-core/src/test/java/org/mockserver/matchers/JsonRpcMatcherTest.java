package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;

import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class JsonRpcMatcherTest {

    @Test
    public void shouldReturnFalseOnReDoSMethodPatternRatherThanHanging() {
        long previousTimeout = ConfigurationProperties.regexMatchingTimeoutMillis();
        try {
            ConfigurationProperties.regexMatchingTimeoutMillis(200L);
            String evilMethod = "(a+)+$";
            String evilInput = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";
            String requestBody = "{\"jsonrpc\": \"2.0\", \"method\": \"" + evilInput + "\", \"id\": 1}";

            long startMillis = System.currentTimeMillis();
            boolean matched = new JsonRpcMatcher(new MockServerLogger(), evilMethod, null).matches(null, requestBody);
            long elapsedMillis = System.currentTimeMillis() - startMillis;

            assertThat("ReDoS method pattern must not match the request", matched, is(false));
            assertThat("regex evaluation should be bounded by the timeout but took " + elapsedMillis + "ms", elapsedMillis < 2_000L, is(true));
        } finally {
            ConfigurationProperties.regexMatchingTimeoutMillis(previousTimeout);
        }
    }

    @Test
    public void shouldMatchSimpleJsonRpcRequest() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 1}"), is(true));
    }

    @Test
    public void shouldMatchJsonRpcRequestWithRegexMethod() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/.*", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 1}"), is(true));
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/.*", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 2}"), is(true));
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/.*", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"resources/read\", \"id\": 3}"), is(false));
    }

    @Test
    public void shouldNotMatchWhenMethodDiffers() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 1}"), is(false));
    }

    @Test
    public void shouldNotMatchWhenJsonRpcVersionMissing() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"method\": \"tools/list\", \"id\": 1}"), is(false));
    }

    @Test
    public void shouldNotMatchWhenJsonRpcVersionWrong() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"1.0\", \"method\": \"tools/list\", \"id\": 1}"), is(false));
    }

    @Test
    public void shouldMatchBatchRequest() {
        String batch = "[" +
            "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"params\": {\"name\": \"foo\"}, \"id\": 1}," +
            "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 2}" +
            "]";
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, batch), is(true));
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/call", null).matches(null, batch), is(true));
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "resources/read", null).matches(null, batch), is(false));
    }

    @Test
    public void shouldNotMatchEmptyBatchRequest() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "[]"), is(false));
    }

    @Test
    public void shouldNotMatchInvalidJson() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{not valid json"), is(false));
    }

    @Test
    public void shouldNotMatchNullBody() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyBody() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, ""), is(false));
    }

    @Test
    public void shouldSupportNotMatcher() {
        assertThat(notMatcher(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null)).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 1}"), is(true));
        assertThat(notMatcher(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null)).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 1}"), is(false));
    }

    @Test
    public void shouldMatchJsonRpcNotification() {
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\"}"), is(true));
        assertThat(new JsonRpcMatcher(new MockServerLogger(), "update", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [1, 2, 3]}"), is(true));
    }
}
