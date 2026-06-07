package org.mockserver.imports;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

public class PostmanCollectionImporterTest {

    private final PostmanCollectionImporter importer = new PostmanCollectionImporter();

    /**
     * A Postman v2.1 collection with:
     * - A folder ("Users") containing:
     *   - A request ("Get User") with 2 saved example responses
     *   - A request ("Delete User") with NO saved example responses (should be skipped)
     * - A top-level request ("Health Check") with 1 saved example response
     * - url-as-object format with path[] and query[]
     */
    private static final String POSTMAN_COLLECTION = "{\n" +
        "  \"info\": {\n" +
        "    \"name\": \"Test API\",\n" +
        "    \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"\n" +
        "  },\n" +
        "  \"item\": [\n" +
        "    {\n" +
        "      \"name\": \"Users\",\n" +
        "      \"item\": [\n" +
        "        {\n" +
        "          \"name\": \"Get User\",\n" +
        "          \"request\": {\n" +
        "            \"method\": \"GET\",\n" +
        "            \"url\": {\n" +
        "              \"raw\": \"http://api.example.com/users/123?fields=name,email\",\n" +
        "              \"path\": [\"users\", \"123\"],\n" +
        "              \"query\": [\n" +
        "                { \"key\": \"fields\", \"value\": \"name,email\" }\n" +
        "              ]\n" +
        "            },\n" +
        "            \"header\": [\n" +
        "              { \"key\": \"Accept\", \"value\": \"application/json\" }\n" +
        "            ]\n" +
        "          },\n" +
        "          \"response\": [\n" +
        "            {\n" +
        "              \"name\": \"200 OK\",\n" +
        "              \"code\": 200,\n" +
        "              \"header\": [\n" +
        "                { \"key\": \"Content-Type\", \"value\": \"application/json\" }\n" +
        "              ],\n" +
        "              \"body\": \"{\\\"id\\\":123,\\\"name\\\":\\\"Alice\\\"}\"\n" +
        "            },\n" +
        "            {\n" +
        "              \"name\": \"404 Not Found\",\n" +
        "              \"code\": 404,\n" +
        "              \"header\": [\n" +
        "                { \"key\": \"Content-Type\", \"value\": \"application/json\" }\n" +
        "              ],\n" +
        "              \"body\": \"{\\\"error\\\":\\\"User not found\\\"}\"\n" +
        "            }\n" +
        "          ]\n" +
        "        },\n" +
        "        {\n" +
        "          \"name\": \"Delete User\",\n" +
        "          \"request\": {\n" +
        "            \"method\": \"DELETE\",\n" +
        "            \"url\": \"http://api.example.com/users/123\"\n" +
        "          },\n" +
        "          \"response\": []\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"name\": \"Health Check\",\n" +
        "      \"request\": {\n" +
        "        \"method\": \"GET\",\n" +
        "        \"url\": \"http://api.example.com/health\"\n" +
        "      },\n" +
        "      \"response\": [\n" +
        "        {\n" +
        "          \"name\": \"200 OK\",\n" +
        "          \"code\": 200,\n" +
        "          \"body\": \"{\\\"status\\\":\\\"healthy\\\"}\"\n" +
        "        }\n" +
        "      ]\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    @Test
    public void importsCorrectNumberOfExpectations() {
        // 2 examples from "Get User" + 1 from "Health Check" = 3
        // "Delete User" is skipped (empty response array)
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);

        assertThat(expectations.size(), is(3));
    }

    @Test
    public void everyExpectationHasAUniqueId() {
        // Regression guard: a single Postman request ("Get User") has two saved example
        // responses. Their generated ids MUST be distinct, otherwise the second silently
        // overwrites the first on upsert.
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);

        long distinctIds = expectations.stream().map(Expectation::getId).distinct().count();
        assertThat((int) distinctIds, is(expectations.size()));
    }

    @Test
    public void firstExampleHasCorrectRequestMatcher() {
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);
        Expectation first = expectations.get(0);

        assertThat(first.getId(), is("postman-0-get-user"));

        HttpRequest request = (HttpRequest) first.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/users/123"));
        assertThat(request.getFirstQueryStringParameter("fields"), is("name,email"));
    }

    @Test
    public void firstExampleHasCorrectResponse() {
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);
        HttpResponse response = expectations.get(0).getHttpResponse();

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("Content-Type"), is("application/json"));
        assertThat(response.getBodyAsString(), containsString("Alice"));
    }

    @Test
    public void secondExampleIs404Response() {
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);
        Expectation second = expectations.get(1);

        // Same request matcher as first (same Postman request, different example)
        HttpRequest request = (HttpRequest) second.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/users/123"));

        HttpResponse response = second.getHttpResponse();
        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getBodyAsString(), containsString("User not found"));
    }

    @Test
    public void healthCheckRequestUsesUrlAsString() {
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);
        Expectation healthCheck = expectations.get(2);

        assertThat(healthCheck.getId(), is("postman-2-health-check"));

        HttpRequest request = (HttpRequest) healthCheck.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/health"));

        HttpResponse response = healthCheck.getHttpResponse();
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("healthy"));
    }

    @Test
    public void skipsRequestsWithoutExamplesWithCount() {
        // "Delete User" has empty response[] — should be skipped
        // The import still succeeds but only produces 3 expectations
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);
        assertThat(expectations.size(), is(3));
    }

    @Test
    public void handlesUrlAsObjectWithPathArray() {
        // The "Get User" request uses url as object with path[] array
        List<Expectation> expectations = importer.importExpectations(POSTMAN_COLLECTION);
        HttpRequest request = (HttpRequest) expectations.get(0).getHttpRequest();
        assertThat(request.getPath().getValue(), is("/users/123"));
    }

    @Test
    public void handlesRequestWithBodyInPostRequest() {
        String collection = "{\n" +
            "  \"info\": { \"name\": \"Test\" },\n" +
            "  \"item\": [\n" +
            "    {\n" +
            "      \"name\": \"Create Item\",\n" +
            "      \"request\": {\n" +
            "        \"method\": \"POST\",\n" +
            "        \"url\": \"http://api.example.com/items\",\n" +
            "        \"body\": {\n" +
            "          \"mode\": \"raw\",\n" +
            "          \"raw\": \"{\\\"name\\\":\\\"widget\\\"}\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"response\": [\n" +
            "        {\n" +
            "          \"name\": \"Created\",\n" +
            "          \"code\": 201,\n" +
            "          \"body\": \"{\\\"id\\\":1,\\\"name\\\":\\\"widget\\\"}\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        List<Expectation> expectations = importer.importExpectations(collection);
        assertThat(expectations.size(), is(1));

        HttpRequest request = (HttpRequest) expectations.get(0).getHttpRequest();
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getBodyAsString(), is("{\"name\":\"widget\"}"));

        HttpResponse response = expectations.get(0).getHttpResponse();
        assertThat(response.getStatusCode(), is(201));
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
            () -> importer.importExpectations("{broken")
        );
        assertThat(ex.getMessage(), containsString("failed to parse Postman collection JSON"));
    }

    @Test
    public void throwsOnMissingInfoObject() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> importer.importExpectations("{\"item\":[]}")
        );
        assertThat(ex.getMessage(), containsString("missing top-level 'info'"));
    }

    @Test
    public void returnsEmptyForCollectionWithNoExamples() {
        String collection = "{\n" +
            "  \"info\": { \"name\": \"Empty\" },\n" +
            "  \"item\": [\n" +
            "    {\n" +
            "      \"name\": \"No Examples\",\n" +
            "      \"request\": { \"method\": \"GET\", \"url\": \"http://example.com/\" },\n" +
            "      \"response\": []\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        List<Expectation> expectations = importer.importExpectations(collection);
        assertThat(expectations.size(), is(0));
    }
}
