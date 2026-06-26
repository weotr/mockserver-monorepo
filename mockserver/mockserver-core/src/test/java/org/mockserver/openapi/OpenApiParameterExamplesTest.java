package org.mockserver.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.Test;
import org.mockserver.model.HttpRequest;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class OpenApiParameterExamplesTest {

    private final OpenAPI openAPI = new OpenAPI();

    @Test
    public void shouldUseExplicitExampleOnParameter() {
        Parameter param = new Parameter().name("limit").in("query").example("25").schema(new IntegerSchema());
        assertThat(OpenApiParameterExamples.getParameterExampleValue(param, openAPI), is("25"));
    }

    @Test
    public void shouldUseSchemaDefaultWhenNoExample() {
        Parameter param = new Parameter().name("limit").in("query")
            .schema(new IntegerSchema()._default(10));
        assertThat(OpenApiParameterExamples.getParameterExampleValue(param, openAPI), is("10"));
    }

    @Test
    public void shouldUseFirstEnumValueWhenNoExampleOrDefault() {
        StringSchema schema = new StringSchema();
        schema.setEnum(Arrays.asList("alpha", "beta"));
        Parameter param = new Parameter().name("mode").in("query").schema(schema);
        assertThat(OpenApiParameterExamples.getParameterExampleValue(param, openAPI), is("alpha"));
    }

    @Test
    public void shouldGenerateFromSchemaTypeWhenNoExampleDefaultOrEnum() {
        Parameter param = new Parameter().name("name").in("query").schema(new StringSchema());
        assertThat(OpenApiParameterExamples.getParameterExampleValue(param, openAPI), is(notNullValue()));
    }

    @Test
    public void shouldReturnNullForOptionalParameterWithNoDerivableValue() {
        Parameter param = new Parameter().name("opt").in("query").required(false);
        assertThat(OpenApiParameterExamples.getParameterExampleValue(param, openAPI), is(nullValue()));
    }

    @Test
    public void shouldReturnFallbackForRequiredParameterWithNoDerivableValue() {
        Parameter param = new Parameter().name("req").in("query").required(true);
        assertThat(OpenApiParameterExamples.getParameterExampleValue(param, openAPI), is("example"));
    }

    @Test
    public void shouldResolvePathParameterIntoTemplate() {
        Operation operation = new Operation().parameters(Collections.singletonList(
            new Parameter().name("id").in("path").required(true).example("99").schema(new StringSchema())
        ));
        assertThat(OpenApiParameterExamples.resolvePath("/items/{id}", operation, openAPI), is("/items/99"));
    }

    @Test
    public void shouldReplaceUnresolvedPathPlaceholderWithExampleLiteral() {
        // operation declares no parameters, so the {id} placeholder has no value
        Operation operation = new Operation();
        assertThat(OpenApiParameterExamples.resolvePath("/items/{id}", operation, openAPI), is("/items/example"));
    }

    @Test
    public void shouldApplyQueryHeaderAndCookieParameters() {
        Operation operation = new Operation().parameters(Arrays.asList(
            new Parameter().name("filter").in("query").required(true).example("active").schema(new StringSchema()),
            new Parameter().name("X-Trace").in("header").required(true).example("trace-1").schema(new StringSchema()),
            new Parameter().name("session").in("cookie").required(true).example("s1").schema(new StringSchema())
        ));
        HttpRequest httpRequest = request().withMethod("GET").withPath("/items");
        OpenApiParameterExamples.applyExampleParameters(httpRequest, operation, openAPI, null);

        assertThat(httpRequest.getFirstQueryStringParameter("filter"), is("active"));
        assertThat(httpRequest.getFirstHeader("X-Trace"), is("trace-1"));
        assertThat(httpRequest.getCookieList().stream().anyMatch(c -> "session".equals(c.getName().getValue()) && "s1".equals(c.getValue().getValue())), is(true));
    }

    @Test
    public void shouldNotApplyAbsentOptionalParameters() {
        Operation operation = new Operation().parameters(Collections.singletonList(
            new Parameter().name("opt").in("query").required(false)
        ));
        HttpRequest httpRequest = request().withMethod("GET").withPath("/items");
        OpenApiParameterExamples.applyExampleParameters(httpRequest, operation, openAPI, null);
        assertThat(httpRequest.getQueryStringParameterList() == null || httpRequest.getQueryStringParameterList().isEmpty(), is(true));
    }
}
