package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;

import static junit.framework.TestCase.*;
import static org.mockserver.matchers.NotMatcher.notMatcher;

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

            assertFalse("ReDoS method pattern must not match the request", matched);
            assertTrue("regex evaluation should be bounded by the timeout but took " + elapsedMillis + "ms", elapsedMillis < 2_000L);
        } finally {
            ConfigurationProperties.regexMatchingTimeoutMillis(previousTimeout);
        }
    }

    @Test
    public void shouldMatchSimpleJsonRpcRequest() {
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 1}"));
    }

    @Test
    public void shouldMatchJsonRpcRequestWithRegexMethod() {
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "tools/.*", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 1}"));
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "tools/.*", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 2}"));
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/.*", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"resources/read\", \"id\": 3}"));
    }

    @Test
    public void shouldNotMatchWhenMethodDiffers() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 1}"));
    }

    @Test
    public void shouldNotMatchWhenJsonRpcVersionMissing() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"method\": \"tools/list\", \"id\": 1}"));
    }

    @Test
    public void shouldNotMatchWhenJsonRpcVersionWrong() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"1.0\", \"method\": \"tools/list\", \"id\": 1}"));
    }

    @Test
    public void shouldMatchBatchRequest() {
        String batch = "[" +
            "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"params\": {\"name\": \"foo\"}, \"id\": 1}," +
            "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 2}" +
            "]";
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, batch));
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "tools/call", null).matches(null, batch));
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "resources/read", null).matches(null, batch));
    }

    @Test
    public void shouldNotMatchEmptyBatchRequest() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "[]"));
    }

    @Test
    public void shouldNotMatchInvalidJson() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{not valid json"));
    }

    @Test
    public void shouldNotMatchNullBody() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, null));
    }

    @Test
    public void shouldNotMatchEmptyBody() {
        assertFalse(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, ""));
    }

    @Test
    public void shouldSupportNotMatcher() {
        assertTrue(notMatcher(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null)).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 1}"));
        assertFalse(notMatcher(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null)).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 1}"));
    }

    @Test
    public void shouldMatchJsonRpcNotification() {
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "tools/list", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\"}"));
        assertTrue(new JsonRpcMatcher(new MockServerLogger(), "update", null).matches(null, "{\"jsonrpc\": \"2.0\", \"method\": \"update\", \"params\": [1, 2, 3]}"));
    }
}
