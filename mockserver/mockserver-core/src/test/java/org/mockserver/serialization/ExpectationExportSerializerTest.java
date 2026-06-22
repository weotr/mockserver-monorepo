package org.mockserver.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.Parameter.param;

/**
 * Tests for the EXPECTATIONS -> OpenAPI export path of {@link ExpectationExportSerializer}.
 * The key invariant is that the output must be schema-VALID OpenAPI 3.0.3 and must never
 * invert matcher semantics (negated matchers must not export as positive operations).
 */
public class ExpectationExportSerializerTest {

    private final ExpectationExportSerializer serializer =
        new ExpectationExportSerializer(new MockServerLogger());
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private JsonNode parseJson(String openApi) throws Exception {
        return objectMapper.readTree(openApi);
    }

    private SwaggerParseResult parseOpenApi(String openApi) {
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        return new OpenAPIV3Parser().readContents(openApi, null, parseOptions);
    }

    private void assertParsesCleanly(String openApi) {
        SwaggerParseResult result = parseOpenApi(openApi);
        assertThat("OpenAPI did not parse into a model: " + openApi,
            result.getOpenAPI(), is(notNullValue()));
        List<String> messages = result.getMessages() == null
            ? Collections.emptyList() : result.getMessages();
        assertThat("swagger-parser reported validity messages: " + messages + "\n--- doc ---\n" + openApi,
            messages, is(empty()));
    }

    // -----------------------------------------------------------------------
    // tests
    // -----------------------------------------------------------------------

    @Test
    public void exportsSchemaValidOpenApi_parsedBySwaggerParser() {
        List<Expectation> expectations = Arrays.asList(
            new Expectation(request().withMethod("GET").withPath("/users"))
                .thenRespond(response().withStatusCode(200).withBody(json("{\"ok\":true}"))),
            new Expectation(request().withMethod("POST").withPath("/orders")
                .withHeader("X-Trace", "abc")
                .withQueryStringParameter("verbose", "true"))
                .thenRespond(response().withStatusCode(201).withReasonPhrase("Created")),
            new Expectation(request().withMethod("DELETE").withPath("nope-no-slash"))
                .thenRespond(response().withStatusCode(204))
        );

        String openApi = serializer.serializeAsOpenApi(expectations);

        assertParsesCleanly(openApi);
    }

    @Test
    public void pathParameterTemplatesThePathKey() throws Exception {
        // path key DOES contain {id} -> parameter must be emitted
        Expectation templated = new Expectation(
            request().withMethod("GET").withPath("/users/{id}")
                .withPathParameter(param("id", "123")))
            .thenRespond(response().withStatusCode(200));
        // path key does NOT contain the placeholder -> parameter must be omitted
        Expectation untemplated = new Expectation(
            request().withMethod("GET").withPath("/accounts")
                .withPathParameter(param("id", "123")))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Arrays.asList(templated, untemplated));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        JsonNode templatedParams = root.at("/paths/~1users~1{id}/get/parameters");
        assertThat(templatedParams.isMissingNode(), is(false));
        boolean hasPathParam = false;
        for (JsonNode p : templatedParams) {
            if ("path".equals(p.path("in").asText()) && "id".equals(p.path("name").asText())) {
                hasPathParam = true;
                assertThat(p.path("required").asBoolean(), is(true));
            }
        }
        assertTrue("expected an in:path parameter named id", hasPathParam);

        // the un-templated operation must NOT carry an in:path parameter
        JsonNode untemplatedParams = root.at("/paths/~1accounts/get/parameters");
        if (!untemplatedParams.isMissingNode()) {
            for (JsonNode p : untemplatedParams) {
                assertThat("un-templated path must not emit an in:path parameter",
                    p.path("in").asText(), is(not(equalTo("path"))));
            }
        }
    }

    @Test
    public void negatedPathMatcherIsNotInverted() throws Exception {
        Expectation negated = new Expectation(
            request().withMethod("GET").withPath(not("/admin")))
            .thenRespond(response().withStatusCode(403));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(negated));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        // there must be NO positive /admin operation
        assertThat(root.at("/paths/~1admin").isMissingNode(), is(true));
        assertThat(openApi, not(containsString("\"/admin\"")));
    }

    @Test
    public void negatedMethodMatcherIsNotInverted() {
        Expectation negated = new Expectation(
            request().withMethod(not("GET")).withPath("/thing"))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(negated));
        assertParsesCleanly(openApi);
        // negated method means the operation is skipped entirely
        assertThat(openApi, not(containsString("\"/thing\"")));
    }

    @Test
    public void negatedHeaderAndQueryParametersAreOmitted() throws Exception {
        Expectation expectation = new Expectation(
            request().withMethod("GET").withPath("/search")
                .withHeader(string("X-Real"), string("yes"))
                .withHeader(not("X-Forbidden"), string("no"))
                .withQueryStringParameter(string("q"), string("term"))
                .withQueryStringParameter(not("secret"), string("x")))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        assertThat(openApi, containsString("X-Real"));
        assertThat(openApi, not(containsString("X-Forbidden")));
        assertThat(openApi, containsString("\"q\""));
        assertThat(openApi, not(containsString("secret")));
    }

    @Test
    public void twoExpectationsSamePathAndMethodMergeResponses() throws Exception {
        Expectation ok = new Expectation(request().withMethod("GET").withPath("/users"))
            .thenRespond(response().withStatusCode(200).withBody(json("[]")));
        Expectation notFound = new Expectation(request().withMethod("GET").withPath("/users"))
            .thenRespond(response().withStatusCode(404).withReasonPhrase("Not Found"));

        String openApi = serializer.serializeAsOpenApi(Arrays.asList(ok, notFound));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        JsonNode getOps = root.at("/paths/~1users/get");
        assertThat("/users should have exactly one get operation", getOps.isMissingNode(), is(false));
        JsonNode responses = getOps.path("responses");
        assertThat(responses.has("200"), is(true));
        assertThat(responses.has("404"), is(true));
    }

    @Test
    public void binaryResponseBodyExportedAsBinarySchema() throws Exception {
        Expectation expectation = new Expectation(request().withMethod("GET").withPath("/image"))
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", "image/png")
                .withBody(binary(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        JsonNode content = root.at("/paths/~1image/get/responses/200/content");
        assertThat(content.has("image/png"), is(true));
        JsonNode schema = content.path("image/png").path("schema");
        assertThat(schema.path("type").asText(), is(equalTo("string")));
        assertThat(schema.path("format").asText(), is(equalTo("binary")));
        // it must NOT be exported as a base64 text/plain example
        assertThat(content.path("image/png").has("example"), is(false));
        assertThat(content.has("text/plain"), is(false));
    }

    @Test
    public void pathWithoutLeadingSlashIsNormalised() throws Exception {
        Expectation expectation = new Expectation(request().withMethod("GET").withPath("widgets"))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        assertThat(root.at("/paths/~1widgets").isMissingNode(), is(false));
        assertThat(root.path("paths").has("widgets"), is(false));
    }

    @Test
    public void emptyPathNormalisesToRoot() throws Exception {
        Expectation expectation = new Expectation(request().withMethod("GET").withPath(""))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        assertThat(root.at("/paths/~1").isMissingNode(), is(false));
    }

    @Test
    public void malformedExpectationDoesNotThrowAndReturnsValidDoc() {
        // request with no method/path/response at all
        Expectation bare = new Expectation(request());
        Expectation noResponse = new Expectation(request().withMethod("GET").withPath("/x"));

        String openApi = serializer.serializeAsOpenApi(Arrays.asList(bare, noResponse));
        // must not throw and must be valid OpenAPI
        assertParsesCleanly(openApi);
    }

    @Test
    public void emptyExpectationListProducesValidEmptyDoc() {
        String openApi = serializer.serializeAsOpenApi(Collections.emptyList());
        assertParsesCleanly(openApi);

        SwaggerParseResult result = parseOpenApi(openApi);
        assertThat(result.getOpenAPI().getOpenapi(), is(notNullValue()));
    }

    @Test
    public void jsonBodyResponseUsesJsonMediaTypeWithExample() throws Exception {
        Expectation expectation = new Expectation(request().withMethod("GET").withPath("/json"))
            .thenRespond(response().withStatusCode(200)
                .withBody(json("{\"hello\":\"world\"}", MediaType.APPLICATION_JSON)));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        JsonNode content = root.at("/paths/~1json/get/responses/200/content");
        assertThat(content.has("application/json"), is(true));
        assertThat(content.path("application/json").path("example").asText(),
            containsString("hello"));
    }

    @Test
    public void pathTemplateWithoutRegisteredParameterSynthesisesPathParam() throws Exception {
        // Defect 1: path key has {orderId} but NO registered path parameter.
        // The doc must still be valid because a path param is synthesised.
        Expectation expectation = new Expectation(
            request().withMethod("GET").withPath("/orders/{orderId}"))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        JsonNode params = root.at("/paths/~1orders~1{orderId}/get/parameters");
        assertThat(params.isMissingNode(), is(false));
        boolean hasSynthesised = false;
        for (JsonNode p : params) {
            if ("path".equals(p.path("in").asText()) && "orderId".equals(p.path("name").asText())) {
                hasSynthesised = true;
                assertThat(p.path("required").asBoolean(), is(true));
                assertThat(p.path("schema").path("type").asText(), is(equalTo("string")));
            }
        }
        assertTrue("expected a synthesised in:path parameter named orderId", hasSynthesised);
    }

    @Test
    public void registeredPathParamIsNotDuplicatedBySynthesis() throws Exception {
        // Defect 1: a {name} placeholder that DOES have a registered path
        // parameter must produce exactly one in:path parameter, not two.
        Expectation expectation = new Expectation(
            request().withMethod("GET").withPath("/users/{id}")
                .withPathParameter(param("id", "123")))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        JsonNode params = root.at("/paths/~1users~1{id}/get/parameters");
        int idPathParams = 0;
        for (JsonNode p : params) {
            if ("path".equals(p.path("in").asText()) && "id".equals(p.path("name").asText())) {
                idPathParams++;
            }
        }
        assertThat("the registered path param must not be duplicated by synthesis",
            idPathParams, is(equalTo(1)));
    }

    @Test
    public void connectMethodOperationIsSkippedAndDocIsValid() {
        // Defect 2: CONNECT (proxy mode) is not a valid OpenAPI operation key.
        Expectation expectation = new Expectation(
            request().withMethod("CONNECT").withPath("/tunnel"))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);
        // the operation must be skipped entirely (no /tunnel path emitted)
        assertThat(openApi, not(containsString("\"/tunnel\"")));
    }

    @Test
    public void garbageMethodOperationIsSkippedAndDocIsValid() {
        // Defect 2: an arbitrary/whitespace method must not be emitted verbatim.
        Expectation expectation = new Expectation(
            request().withMethod("FOO BAR").withPath("/garbage"))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);
        assertThat(openApi, not(containsString("\"/garbage\"")));
    }

    @Test
    public void distinctOperationsSharingAnExplicitIdGetDistinctOperationIds() throws Exception {
        // Defect 3: two DIFFERENT operations whose expectations share an explicit
        // id must not emit a repeated operationId.
        Expectation x = new Expectation(request().withMethod("GET").withPath("/x"))
            .withId("shared-id")
            .thenRespond(response().withStatusCode(200));
        Expectation y = new Expectation(request().withMethod("GET").withPath("/y"))
            .withId("shared-id")
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Arrays.asList(x, y));
        assertParsesCleanly(openApi);

        JsonNode root = parseJson(openApi);
        String idX = root.at("/paths/~1x/get/operationId").asText();
        String idY = root.at("/paths/~1y/get/operationId").asText();
        assertThat("both operations must be present", idX.isEmpty() || idY.isEmpty(), is(false));
        assertThat("distinct operations must have distinct operationIds",
            idX, is(not(equalTo(idY))));
    }

    @Test
    public void plainHttpRequestPathParamWithoutValueDoesNotProduceInvalidDoc() {
        // path declares no placeholder but a path param is present -> param omitted, still valid
        Expectation expectation = new Expectation(
            request().withMethod("GET").withPath("/no-template")
                .withPathParameter(param("id", "9")))
            .thenRespond(response().withStatusCode(200));

        String openApi = serializer.serializeAsOpenApi(Collections.singletonList(expectation));
        assertParsesCleanly(openApi);
    }
}
