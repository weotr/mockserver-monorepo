package org.mockserver.mock.diff;

import org.junit.Test;
import org.mockserver.model.HttpRequest;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class TrafficDiffEngineTest {

    private final TrafficDiffEngine engine = new TrafficDiffEngine();

    @Test
    public void shouldReturnNoDiffsForIdenticalRequests() {
        HttpRequest a = request().withMethod("GET").withPath("/api/users");
        HttpRequest b = request().withMethod("GET").withPath("/api/users");
        List<FieldDiff> diffs = engine.diff(a, b);
        assertThat(diffs, is(empty()));
    }

    @Test
    public void shouldDetectMethodDifference() {
        HttpRequest expected = request().withMethod("GET").withPath("/api");
        HttpRequest actual = request().withMethod("POST").withPath("/api");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasSize(1));
        assertThat(diffs.get(0).getField(), is("method"));
        assertThat(diffs.get(0).getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(diffs.get(0).getExpectedValue(), is("GET"));
        assertThat(diffs.get(0).getActualValue(), is("POST"));
    }

    @Test
    public void shouldDetectPathDifference() {
        HttpRequest expected = request().withPath("/api/users");
        HttpRequest actual = request().withPath("/api/items");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("path"))));
        FieldDiff pathDiff = diffs.stream().filter(d -> "path".equals(d.getField())).findFirst().orElseThrow();
        assertThat(pathDiff.getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(pathDiff.getExpectedValue(), is("/api/users"));
        assertThat(pathDiff.getActualValue(), is("/api/items"));
    }

    @Test
    public void shouldDetectBodyDifference() {
        HttpRequest expected = request().withPath("/api").withBody("hello");
        HttpRequest actual = request().withPath("/api").withBody("world");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("body"))));
        FieldDiff bodyDiff = diffs.stream().filter(d -> "body".equals(d.getField())).findFirst().orElseThrow();
        assertThat(bodyDiff.getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(bodyDiff.getExpectedValue(), is("hello"));
        assertThat(bodyDiff.getActualValue(), is("world"));
    }

    @Test
    public void shouldDetectAddedHeader() {
        HttpRequest expected = request().withPath("/api");
        HttpRequest actual = request().withPath("/api").withHeader("X-New-Header", "value");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("header.x-new-header"))));
        FieldDiff headerDiff = diffs.stream().filter(d -> d.getField().contains("x-new-header")).findFirst().orElseThrow();
        assertThat(headerDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
        assertThat(headerDiff.getActualValue(), is("value"));
    }

    @Test
    public void shouldDetectRemovedHeader() {
        HttpRequest expected = request().withPath("/api").withHeader("X-Old-Header", "value");
        HttpRequest actual = request().withPath("/api");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("header.x-old-header"))));
        FieldDiff headerDiff = diffs.stream().filter(d -> d.getField().contains("x-old-header")).findFirst().orElseThrow();
        assertThat(headerDiff.getDiffType(), is(FieldDiff.DiffType.REMOVED));
        assertThat(headerDiff.getExpectedValue(), is("value"));
    }

    @Test
    public void shouldDetectChangedHeader() {
        HttpRequest expected = request().withPath("/api").withHeader("Accept", "text/html");
        HttpRequest actual = request().withPath("/api").withHeader("Accept", "application/json");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("header.accept"))));
        FieldDiff headerDiff = diffs.stream().filter(d -> d.getField().contains("accept")).findFirst().orElseThrow();
        assertThat(headerDiff.getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(headerDiff.getExpectedValue(), is("text/html"));
        assertThat(headerDiff.getActualValue(), is("application/json"));
    }

    @Test
    public void shouldReturnNoDiffsForIdenticalRequestsWithHeaders() {
        HttpRequest a = request().withMethod("GET").withPath("/api").withHeader("Accept", "application/json");
        HttpRequest b = request().withMethod("GET").withPath("/api").withHeader("Accept", "application/json");
        assertThat(engine.diff(a, b), is(empty()));
    }

    @Test
    public void shouldDetectAddedQueryParam() {
        HttpRequest expected = request().withPath("/api");
        HttpRequest actual = request().withPath("/api").withQueryStringParameter("page", "1");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("queryParam.page"))));
        FieldDiff paramDiff = diffs.stream().filter(d -> d.getField().contains("page")).findFirst().orElseThrow();
        assertThat(paramDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
    }

    @Test
    public void shouldDetectRemovedQueryParam() {
        HttpRequest expected = request().withPath("/api").withQueryStringParameter("page", "1");
        HttpRequest actual = request().withPath("/api");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("queryParam.page"))));
        FieldDiff paramDiff = diffs.stream().filter(d -> d.getField().contains("page")).findFirst().orElseThrow();
        assertThat(paramDiff.getDiffType(), is(FieldDiff.DiffType.REMOVED));
    }

    @Test
    public void shouldDetectAddedCookie() {
        HttpRequest expected = request().withPath("/api");
        HttpRequest actual = request().withPath("/api").withCookie("session", "abc123");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("cookie.session"))));
        FieldDiff cookieDiff = diffs.stream().filter(d -> d.getField().contains("session")).findFirst().orElseThrow();
        assertThat(cookieDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
        assertThat(cookieDiff.getActualValue(), is("abc123"));
    }

    @Test
    public void shouldDetectRemovedCookie() {
        HttpRequest expected = request().withPath("/api").withCookie("session", "abc123");
        HttpRequest actual = request().withPath("/api");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("cookie.session"))));
        FieldDiff cookieDiff = diffs.stream().filter(d -> d.getField().contains("session")).findFirst().orElseThrow();
        assertThat(cookieDiff.getDiffType(), is(FieldDiff.DiffType.REMOVED));
    }

    @Test
    public void shouldDetectChangedCookie() {
        HttpRequest expected = request().withPath("/api").withCookie("session", "abc123");
        HttpRequest actual = request().withPath("/api").withCookie("session", "xyz789");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("cookie.session"))));
        FieldDiff cookieDiff = diffs.stream().filter(d -> d.getField().contains("session")).findFirst().orElseThrow();
        assertThat(cookieDiff.getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(cookieDiff.getExpectedValue(), is("abc123"));
        assertThat(cookieDiff.getActualValue(), is("xyz789"));
    }

    @Test
    public void shouldReturnAddedDiffWhenExpectedIsNull() {
        HttpRequest actual = request().withPath("/api");
        List<FieldDiff> diffs = engine.diff(null, actual);
        assertThat(diffs, hasSize(1));
        assertThat(diffs.get(0).getDiffType(), is(FieldDiff.DiffType.ADDED));
        assertThat(diffs.get(0).getField(), is("request"));
    }

    @Test
    public void shouldReturnRemovedDiffWhenActualIsNull() {
        HttpRequest expected = request().withPath("/api");
        List<FieldDiff> diffs = engine.diff(expected, null);
        assertThat(diffs, hasSize(1));
        assertThat(diffs.get(0).getDiffType(), is(FieldDiff.DiffType.REMOVED));
        assertThat(diffs.get(0).getField(), is("request"));
    }

    @Test
    public void shouldReturnEmptyDiffsWhenBothNull() {
        assertThat(engine.diff(null, null), is(empty()));
    }

    @Test
    public void shouldDetectMultipleDifferences() {
        HttpRequest expected = request()
            .withMethod("GET")
            .withPath("/api/users")
            .withHeader("Accept", "text/html")
            .withCookie("session", "old");
        HttpRequest actual = request()
            .withMethod("POST")
            .withPath("/api/items")
            .withHeader("Accept", "application/json")
            .withCookie("session", "new");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        // method, path, header.accept, cookie.session = 4 diffs
        assertThat(diffs, hasSize(4));
        assertThat(diffs, hasItem(hasProperty("field", is("method"))));
        assertThat(diffs, hasItem(hasProperty("field", is("path"))));
        assertThat(diffs, hasItem(hasProperty("field", is("header.accept"))));
        assertThat(diffs, hasItem(hasProperty("field", is("cookie.session"))));
    }

    @Test
    public void shouldDetectBodyAddedWhenExpectedHasNoBody() {
        HttpRequest expected = request().withPath("/api");
        HttpRequest actual = request().withPath("/api").withBody("some content");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("body"))));
        FieldDiff bodyDiff = diffs.stream().filter(d -> "body".equals(d.getField())).findFirst().orElseThrow();
        assertThat(bodyDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
    }

    @Test
    public void shouldDetectBodyRemovedWhenActualHasNoBody() {
        HttpRequest expected = request().withPath("/api").withBody("some content");
        HttpRequest actual = request().withPath("/api");
        List<FieldDiff> diffs = engine.diff(expected, actual);
        assertThat(diffs, hasItem(hasProperty("field", is("body"))));
        FieldDiff bodyDiff = diffs.stream().filter(d -> "body".equals(d.getField())).findFirst().orElseThrow();
        assertThat(bodyDiff.getDiffType(), is(FieldDiff.DiffType.REMOVED));
    }
}
