package org.mockserver.mock.drift;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class DriftAnalyzerTest {

    @Test
    public void detectsStatusDrift() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200));
        HttpResponse real = response().withStatusCode(422);

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.STATUS));
        assertThat(records.get(0).getExpectedValue(), is("200"));
        assertThat(records.get(0).getActualValue(), is("422"));
        assertThat(records.get(0).getConfidence(), is(1.0));
    }

    @Test
    public void detectsSchemaFieldAdded() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200).withBody("{\"name\":\"Alice\"}", MediaType.JSON_UTF_8));
        HttpResponse real = response().withStatusCode(200)
            .withBody("{\"name\":\"Alice\",\"email\":\"a@b.com\"}", MediaType.JSON_UTF_8);

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.SCHEMA_FIELD_ADDED));
        assertThat(records.get(0).getField(), is("$.email"));
    }

    @Test
    public void detectsSchemaFieldRemoved() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withBody("{\"name\":\"Alice\",\"role\":\"admin\"}", MediaType.JSON_UTF_8));
        HttpResponse real = response().withStatusCode(200)
            .withBody("{\"name\":\"Alice\"}", MediaType.JSON_UTF_8);

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.SCHEMA_FIELD_REMOVED));
        assertThat(records.get(0).getField(), is("$.role"));
    }

    @Test
    public void detectsSchemaTypeChanged() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withBody("{\"count\":42}", MediaType.JSON_UTF_8));
        HttpResponse real = response().withStatusCode(200)
            .withBody("{\"count\":\"forty-two\"}", MediaType.JSON_UTF_8);

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.SCHEMA_TYPE_CHANGED));
        assertThat(records.get(0).getField(), is("$.count"));
    }

    @Test
    public void detectsHeaderAdded() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withHeader("content-type", "application/json"));
        HttpResponse real = response().withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withHeader("x-custom", "value");

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.HEADER_ADDED));
        assertThat(records.get(0).getField(), is("header.x-custom"));
    }

    @Test
    public void detectsHeaderRemoved() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withHeader("x-custom", "value"));
        HttpResponse real = response().withStatusCode(200)
            .withHeader("content-type", "application/json");

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.HEADER_REMOVED));
        assertThat(records.get(0).getField(), is("header.x-custom"));
    }

    @Test
    public void detectsHeaderChanged() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withHeader("content-type", "application/json"));
        HttpResponse real = response().withStatusCode(200)
            .withHeader("content-type", "text/html");

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(10);
        assertThat(records, hasSize(1));
        assertThat(records.get(0).getDriftType(), is(DriftType.HEADER_CHANGED));
        assertThat(records.get(0).getField(), is("header.content-type"));
        assertThat(records.get(0).getExpectedValue(), is("application/json"));
        assertThat(records.get(0).getActualValue(), is("text/html"));
    }

    @Test
    public void noFalsePositivesWhenResponsesMatch() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withBody("{\"name\":\"Alice\"}", MediaType.JSON_UTF_8));
        // Same status, same fields, different value — no structural drift
        HttpResponse real = response().withStatusCode(200)
            .withBody("{\"name\":\"Bob\"}", MediaType.JSON_UTF_8);

        analyzer.analyse(expectation, real);

        assertThat(store.getRecent(10), is(empty()));
    }

    @Test
    public void skipsNonSemanticHeaders() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200));
        HttpResponse real = response().withStatusCode(200)
            .withHeader("date", "Mon, 01 Jan 2024 00:00:00 GMT")
            .withHeader("content-length", "42")
            .withHeader("connection", "keep-alive")
            .withHeader("server", "nginx");

        analyzer.analyse(expectation, real);

        assertThat(store.getRecent(10), is(empty()));
    }

    @Test
    public void nullInputsAreHandledGracefully() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        analyzer.analyse(null, response());
        analyzer.analyse(new Expectation(request()), null);
        assertThat(store.getRecent(10), is(empty()));
    }

    @Test
    public void nonResponseActionExpectationIsSkipped() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        // Expectation with no response action
        Expectation expectation = new Expectation(request().withPath("/api"));

        analyzer.analyse(expectation, response().withStatusCode(200));

        assertThat(store.getRecent(10), is(empty()));
    }

    @Test
    public void detectsMultipleDriftTypes() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withHeader("x-version", "1.0")
                .withBody("{\"name\":\"Alice\"}", MediaType.JSON_UTF_8));
        HttpResponse real = response().withStatusCode(500)
            .withHeader("x-version", "2.0")
            .withBody("{\"name\":\"Alice\",\"newField\":true}", MediaType.JSON_UTF_8);

        analyzer.analyse(expectation, real);

        List<DriftRecord> records = store.getRecent(100);
        // Should detect: status drift, header changed, schema field added
        assertThat(records.size(), greaterThan(2));
    }

    @Test
    public void nonJsonBodiesSkipSchemaDrift() {
        DriftStore store = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(store);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200)
                .withBody("plain text body"));
        HttpResponse real = response().withStatusCode(200)
            .withBody("different plain text body");

        analyzer.analyse(expectation, real);

        assertThat(store.getRecent(10), is(empty()));
    }
}
