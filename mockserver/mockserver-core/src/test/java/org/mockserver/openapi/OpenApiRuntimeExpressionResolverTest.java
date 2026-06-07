package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;

public class OpenApiRuntimeExpressionResolverTest {

    // --- containsExpression ---

    @Test
    public void shouldDetectExpressionInString() {
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression("{$request.body#/callbackUrl}/events"), is(true));
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression("{$request.query.id}"), is(true));
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression("{$url}"), is(true));
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression("https://example.com/path"), is(false));
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression("/plain/path"), is(false));
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression(null), is(false));
        assertThat(OpenApiRuntimeExpressionResolver.containsExpression(""), is(false));
    }

    // --- No-op guarantee: plain request returned unchanged (same instance) ---

    @Test
    public void shouldReturnSameInstanceWhenNoExpressions() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/plain/webhook")
            .withHeader("Host", "example.com")
            .withHeader("Content-Type", "application/json")
            .withBody("{\"event\":\"created\"}");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withPath("/api/subscribe")
            .withBody("{\"callbackUrl\":\"https://client.example.com\"}");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — strict no-op: same reference returned
        assertThat(result, sameInstance(afterAction));
    }

    @Test
    public void shouldReturnSameInstanceWhenNullAfterAction() {
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(null, request());
        assertThat(result == null, is(true));
    }

    @Test
    public void shouldReturnSameInstanceWhenNullTriggeringRequest() {
        HttpRequest afterAction = request().withPath("/webhook");
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, null);
        assertThat(result, sameInstance(afterAction));
    }

    // --- Body JSON Pointer ---

    @Test
    public void shouldResolveBodyJsonPointer() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("{$request.body#/callbackUrl}/events");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withBody("{\"callbackUrl\":\"https://client.example.com\",\"name\":\"test\"}");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — expression resolved to the JSON field value, URI parsed into path + Host
        assertThat(result.getPath().getValue(), is("/events"));
        assertThat(result.getFirstHeader("Host"), is("client.example.com"));
        assertThat(result.isSecure(), is(true));
    }

    @Test
    public void shouldResolveNestedBodyJsonPointer() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("{$request.body#/config/webhookUrl}");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withBody("{\"config\":{\"webhookUrl\":\"http://hooks.local:9090/notify\"}}");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getPath().getValue(), is("/notify"));
        assertThat(result.getFirstHeader("Host"), is("hooks.local:9090"));
        assertThat(result.isSecure(), is(false));
    }

    @Test
    public void shouldResolveBodyJsonPointerToNumericValue() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/notify/{$request.body#/id}");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withBody("{\"id\":42}");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — numeric JSON values are rendered as string
        assertThat(result.getPath().getValue(), is("/notify/42"));
    }

    @Test
    public void shouldReturnEmptyForMissingJsonPointer() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/notify{$request.body#/nonexistent}");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withBody("{\"other\":\"value\"}");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — missing pointer resolves to empty string
        assertThat(result.getPath().getValue(), is("/notify"));
    }

    @Test
    public void shouldReturnEmptyForNonJsonBody() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("{$request.body#/url}/path");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withBody("this is not json");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — non-JSON body: expression resolves to empty
        assertThat(result.getPath().getValue(), is("/path"));
    }

    // --- Query parameter ---

    @Test
    public void shouldResolveQueryParameter() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback/{$request.query.callbackId}");
        HttpRequest triggering = request()
            .withMethod("GET")
            .withPath("/api/register")
            .withQueryStringParameter("callbackId", "abc-123");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getPath().getValue(), is("/callback/abc-123"));
    }

    @Test
    public void shouldReturnEmptyForMissingQueryParameter() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback/{$request.query.missing}");
        HttpRequest triggering = request()
            .withMethod("GET")
            .withPath("/api/register");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getPath().getValue(), is("/callback/"));
    }

    // --- Header ---

    @Test
    public void shouldResolveHeader() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback")
            .withHeader("X-Correlation-Id", "{$request.header.X-Request-Id}");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withHeader("X-Request-Id", "req-456");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getFirstHeader("X-Correlation-Id"), is("req-456"));
    }

    @Test
    public void shouldReturnEmptyForMissingHeader() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback")
            .withHeader("X-Correlation-Id", "{$request.header.X-Missing}");
        HttpRequest triggering = request()
            .withMethod("POST");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getFirstHeader("X-Correlation-Id"), is(""));
    }

    // --- Method ---

    @Test
    public void shouldResolveMethod() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback")
            .withHeader("X-Original-Method", "{$request.method}");
        HttpRequest triggering = request()
            .withMethod("PUT")
            .withPath("/api/resource");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getFirstHeader("X-Original-Method"), is("PUT"));
    }

    // --- URL ---

    @Test
    public void shouldResolveUrl() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback")
            .withHeader("X-Original-Url", "{$url}");
        HttpRequest triggering = request()
            .withMethod("GET")
            .withPath("/api/items")
            .withHeader("Host", "api.example.com")
            .withSecure(true);

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getFirstHeader("X-Original-Url"), is("https://api.example.com/api/items"));
    }

    // --- Response expressions (out of scope) ---

    @Test
    public void shouldReplaceResponseExpressionWithEmpty() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback/{$response.body#/id}");
        HttpRequest triggering = request()
            .withMethod("POST");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — response expressions are out of scope and resolve to empty
        assertThat(result.getPath().getValue(), is("/callback/"));
    }

    // --- Multiple expressions in one string ---

    @Test
    public void shouldResolveMultipleExpressionsInSameString() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("{$request.body#/baseUrl}/notify/{$request.query.type}");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withPath("/api/subscribe")
            .withQueryStringParameter("type", "webhook")
            .withBody("{\"baseUrl\":\"https://hooks.example.com\"}");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — full URL from expression is parsed into path/host/secure
        assertThat(result.getPath().getValue(), is("/notify/webhook"));
        assertThat(result.getFirstHeader("Host"), is("hooks.example.com"));
        assertThat(result.isSecure(), is(true));
    }

    // --- Body expression resolution ---

    @Test
    public void shouldResolveExpressionsInBody() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback")
            .withBody("{\"originalMethod\":\"{$request.method}\",\"correlationId\":\"{$request.header.X-Req-Id}\"}");
        HttpRequest triggering = request()
            .withMethod("DELETE")
            .withHeader("X-Req-Id", "corr-789");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getBodyAsString(), is("{\"originalMethod\":\"DELETE\",\"correlationId\":\"corr-789\"}"));
    }

    // --- Path parameter (best-effort) ---

    @Test
    public void shouldResolvePathParameter() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback/{$request.path.petId}");
        HttpRequest triggering = request()
            .withMethod("GET")
            .withPath("/pets/42")
            .withPathParameter("petId", "42");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getPath().getValue(), is("/callback/42"));
    }

    @Test
    public void shouldReturnEmptyForMissingPathParameter() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback/{$request.path.missing}");
        HttpRequest triggering = request()
            .withMethod("GET")
            .withPath("/pets/42");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getPath().getValue(), is("/callback/"));
    }

    // --- Unknown expression ---

    @Test
    public void shouldReturnEmptyForUnknownExpression() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("/callback/{$unknown.expression}");
        HttpRequest triggering = request()
            .withMethod("POST");

        // when
        HttpRequest result = OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then
        assertThat(result.getPath().getValue(), is("/callback/"));
    }

    // --- Regression: does not mutate original ---

    @Test
    public void shouldNotMutateOriginalAfterActionRequest() {
        // given
        HttpRequest afterAction = request()
            .withMethod("POST")
            .withPath("{$request.body#/callbackUrl}/events");
        HttpRequest triggering = request()
            .withMethod("POST")
            .withBody("{\"callbackUrl\":\"https://client.example.com\"}");
        String originalPath = afterAction.getPath().getValue();

        // when
        OpenApiRuntimeExpressionResolver.resolve(afterAction, triggering);

        // then — original is NOT mutated
        assertThat(afterAction.getPath().getValue(), is(originalPath));
    }
}
