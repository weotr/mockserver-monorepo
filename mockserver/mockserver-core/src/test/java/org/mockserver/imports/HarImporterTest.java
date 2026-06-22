package org.mockserver.imports;

import org.junit.Test;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;

public class HarImporterTest {

    private final HarImporter importer = new HarImporter();

    private static final String SIMPLE_HAR = "{\n" +
        "  \"log\": {\n" +
        "    \"version\": \"1.2\",\n" +
        "    \"entries\": [\n" +
        "      {\n" +
        "        \"request\": {\n" +
        "          \"method\": \"GET\",\n" +
        "          \"url\": \"https://api.example.com/users?page=1&limit=10\",\n" +
        "          \"headers\": [\n" +
        "            { \"name\": \"Accept\", \"value\": \"application/json\" },\n" +
        "            { \"name\": \"X-Api-Key\", \"value\": \"test-key\" },\n" +
        "            { \"name\": \"Date\", \"value\": \"Mon, 01 Jan 2024 00:00:00 GMT\" },\n" +
        "            { \"name\": \"User-Agent\", \"value\": \"Mozilla/5.0\" }\n" +
        "          ]\n" +
        "        },\n" +
        "        \"response\": {\n" +
        "          \"status\": 200,\n" +
        "          \"headers\": [\n" +
        "            { \"name\": \"Content-Type\", \"value\": \"application/json\" },\n" +
        "            { \"name\": \"Date\", \"value\": \"Mon, 01 Jan 2024 00:00:00 GMT\" },\n" +
        "            { \"name\": \"X-Request-Id\", \"value\": \"abc-123\" },\n" +
        "            { \"name\": \"X-Custom\", \"value\": \"custom-value\" }\n" +
        "          ],\n" +
        "          \"content\": {\n" +
        "            \"text\": \"{\\\"users\\\":[{\\\"id\\\":1}]}\"\n" +
        "          }\n" +
        "        }\n" +
        "      },\n" +
        "      {\n" +
        "        \"request\": {\n" +
        "          \"method\": \"POST\",\n" +
        "          \"url\": \"https://api.example.com/users\",\n" +
        "          \"headers\": [\n" +
        "            { \"name\": \"Content-Type\", \"value\": \"application/json\" }\n" +
        "          ],\n" +
        "          \"postData\": {\n" +
        "            \"text\": \"{\\\"name\\\":\\\"Alice\\\"}\"\n" +
        "          }\n" +
        "        },\n" +
        "        \"response\": {\n" +
        "          \"status\": 201,\n" +
        "          \"headers\": [\n" +
        "            { \"name\": \"Content-Type\", \"value\": \"application/json\" }\n" +
        "          ],\n" +
        "          \"content\": {\n" +
        "            \"text\": \"{\\\"id\\\":2,\\\"name\\\":\\\"Alice\\\"}\"\n" +
        "          }\n" +
        "        }\n" +
        "      }\n" +
        "    ]\n" +
        "  }\n" +
        "}";

    @Test
    public void importsTwoEntriesAsExpectations() {
        List<Expectation> expectations = importer.importExpectations(SIMPLE_HAR);

        assertThat(expectations.size(), is(2));
    }

    @Test
    public void firstEntryHasCorrectMethodPathAndQuery() {
        List<Expectation> expectations = importer.importExpectations(SIMPLE_HAR);
        Expectation first = expectations.get(0);

        assertThat(first.getId(), is("har-0"));
        HttpRequest request = (HttpRequest) first.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/users"));

        // Query parameters should be parsed
        assertThat(request.getFirstQueryStringParameter("page"), is("1"));
        assertThat(request.getFirstQueryStringParameter("limit"), is("10"));
    }

    @Test
    public void firstEntryFiltersVolatileRequestHeaders() {
        List<Expectation> expectations = importer.importExpectations(SIMPLE_HAR);
        HttpRequest request = (HttpRequest) expectations.get(0).getHttpRequest();

        // X-Api-Key is NOT volatile, so it survives header filtering — but it IS
        // sensitive, so import redaction (on by default) replaces its value with
        // the placeholder rather than leaking the real key.
        assertThat(request.getFirstHeader("X-Api-Key"), is(FixtureRedactor.REDACTED_PLACEHOLDER));

        // Date, User-Agent, Accept are volatile, should be filtered
        assertThat(request.getFirstHeader("Date"), is(""));
        assertThat(request.getFirstHeader("User-Agent"), is(""));
        assertThat(request.getFirstHeader("Accept"), is(""));
    }

    @Test
    public void firstEntryHasCorrectResponseStatusAndBody() {
        List<Expectation> expectations = importer.importExpectations(SIMPLE_HAR);
        HttpResponse response = expectations.get(0).getHttpResponse();

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("{\"users\":[{\"id\":1}]}"));
    }

    @Test
    public void firstEntryFiltersVolatileResponseHeaders() {
        List<Expectation> expectations = importer.importExpectations(SIMPLE_HAR);
        HttpResponse response = expectations.get(0).getHttpResponse();

        // Content-Type and X-Custom should be present
        assertThat(response.getFirstHeader("Content-Type"), is("application/json"));
        assertThat(response.getFirstHeader("X-Custom"), is("custom-value"));

        // Date and X-Request-Id are volatile, should be filtered
        assertThat(response.getFirstHeader("Date"), is(""));
        assertThat(response.getFirstHeader("X-Request-Id"), is(""));
    }

    @Test
    public void secondEntryHasPostBodyAndCorrectResponse() {
        List<Expectation> expectations = importer.importExpectations(SIMPLE_HAR);
        Expectation second = expectations.get(1);

        assertThat(second.getId(), is("har-1"));

        HttpRequest request = (HttpRequest) second.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getPath().getValue(), is("/users"));
        assertThat(request.getBodyAsString(), is("{\"name\":\"Alice\"}"));

        HttpResponse response = second.getHttpResponse();
        assertThat(response.getStatusCode(), is(201));
        assertThat(response.getBodyAsString(), containsString("Alice"));
    }

    @Test
    public void decodesBase64ResponseBody() {
        String base64Body = Base64.getEncoder().encodeToString("Hello, World!".getBytes());
        String har = "{\n" +
            "  \"log\": {\n" +
            "    \"entries\": [\n" +
            "      {\n" +
            "        \"request\": { \"method\": \"GET\", \"url\": \"http://example.com/data\" },\n" +
            "        \"response\": {\n" +
            "          \"status\": 200,\n" +
            "          \"content\": {\n" +
            "            \"text\": \"" + base64Body + "\",\n" +
            "            \"encoding\": \"base64\"\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";

        List<Expectation> expectations = importer.importExpectations(har);
        assertThat(expectations.size(), is(1));
        assertThat(expectations.get(0).getHttpResponse().getBodyAsString(), is("Hello, World!"));
    }

    @Test
    public void usesHarQueryStringArrayWhenPresent() {
        String har = "{\n" +
            "  \"log\": {\n" +
            "    \"entries\": [\n" +
            "      {\n" +
            "        \"request\": {\n" +
            "          \"method\": \"GET\",\n" +
            "          \"url\": \"http://example.com/search\",\n" +
            "          \"queryString\": [\n" +
            "            { \"name\": \"q\", \"value\": \"test\" },\n" +
            "            { \"name\": \"lang\", \"value\": \"en\" }\n" +
            "          ]\n" +
            "        },\n" +
            "        \"response\": { \"status\": 200 }\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";

        List<Expectation> expectations = importer.importExpectations(har);
        HttpRequest request = (HttpRequest) expectations.get(0).getHttpRequest();
        assertThat(request.getFirstQueryStringParameter("q"), is("test"));
        assertThat(request.getFirstQueryStringParameter("lang"), is("en"));
    }

    @Test
    public void toleratesMissingOptionalFields() {
        // Minimal entry: no headers, no body, no query
        String har = "{\n" +
            "  \"log\": {\n" +
            "    \"entries\": [\n" +
            "      {\n" +
            "        \"request\": { \"method\": \"GET\", \"url\": \"http://example.com/health\" },\n" +
            "        \"response\": { \"status\": 204 }\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";

        List<Expectation> expectations = importer.importExpectations(har);
        assertThat(expectations.size(), is(1));
        assertThat(expectations.get(0).getHttpResponse().getStatusCode(), is(204));
    }

    @Test
    public void throwsOnNullInput() {
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations(null));
    }

    @Test
    public void throwsOnBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> importer.importExpectations("   "));
    }

    @Test
    public void throwsOnMalformedJson() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> importer.importExpectations("{not valid json")
        );
        assertThat(ex.getMessage(), containsString("failed to parse HAR JSON"));
    }

    @Test
    public void throwsOnMissingLogObject() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> importer.importExpectations("{\"foo\":\"bar\"}")
        );
        assertThat(ex.getMessage(), containsString("missing top-level 'log'"));
    }

    @Test
    public void returnsEmptyListForEmptyEntriesArray() {
        String har = "{\"log\":{\"entries\":[]}}";
        List<Expectation> expectations = importer.importExpectations(har);
        assertThat(expectations.size(), is(0));
    }
}
