package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matcher;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.time.EpochService;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.ParameterBody.params;
import static org.mockserver.model.XmlBody.xml;
import static org.slf4j.event.Level.INFO;

/**
 * @author jamesdbloom
 */
public class JavaScriptTemplateEngineTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static boolean originalFixedTime;
    private static final Configuration configuration = configuration();

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Mock
    private MockServerLogger mockServerLogger;

    @BeforeClass
    public static void fixTime() {
        originalFixedTime = EpochService.fixedTime;
        EpochService.fixedTime = true;
    }

    @AfterClass
    public static void fixTimeReset() {
        EpochService.fixedTime = originalFixedTime;
    }

    @Before
    public void setupTestFixture() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    public static void graalJsAvailable() {
        assumeThat("GraalVM Polyglot API available", JavaScriptTemplateEngine.isPolyglotAvailable(), is(true));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithECMA6() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "var customer = { name: \"Foo\" }" + NEW_LINE +
            "var card = { amount: 7, product: \"Bar\", unitprice: 42 }" + NEW_LINE +
            "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': `Hello ${customer.name}, want to buy ${card.amount} ${card.product} for a total of ${card.amount * card.unitprice} bucks?`" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("Hello Foo, want to buy 7 Bar for a total of 294 bucks?")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"Hello Foo, want to buy 7 Bar for a total of 294 bucks?\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithMethodPathAndHeader() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'method\\': \\'' + request.method + '\\', \\'path\\': \\'' + request.path + '\\', \\'headers\\': \\'' + request.headers.host[0] + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithParametersCookiesAndBody() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'queryStringParameters\\': \\'' + request.queryStringParameters.nameOne[0] + ',' + request.queryStringParameters.nameTwo[0] + ',' + request.queryStringParameters.nameTwo[1] + '\\'," +
            " \\'pathParameters\\': \\'' + request.pathParameters.nameOne[0] + ',' + request.pathParameters.nameTwo[0] + ',' + request.pathParameters.nameTwo[1] + '\\'," +
            " \\'cookies\\': \\'' + request.cookies.session + '\\'," +
            " \\'body\\': \\'' + request.body + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withQueryStringParameter("nameOne", "queryValueOne")
            .withQueryStringParameter("nameTwo", "queryValueTwoOne", "queryValueTwoTwo")
            .withPathParameter("nameOne", "pathValueOne")
            .withPathParameter("nameTwo", "pathValueTwoOne", "pathValueTwoTwo")
            .withMethod("POST")
            .withCookie("session", "some_session_id")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody(
                    "{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithDynamicValuesDateAndUUID() throws JsonProcessingException {
        boolean originalFixedUUID = UUIDService.fixedUUID;
        boolean originalFixedTime = TimeService.fixedTime;
        try {
            // given
        graalJsAvailable();
            UUIDService.fixedUUID = true;
            TimeService.fixedTime = true;
            String template = "return {" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': '{\\'date\\': \\'' + now + '\\', \\'date_epoch\\': \\'' + now_epoch + '\\', \\'date_iso_8601\\': \\'' + now_iso_8601 + '\\', \\'date_rfc_1123\\': \\'' + now_rfc_1123 + '\\', \\'uuids\\': [\\'' + uuid + '\\', \\'' + uuid + '\\'] }'" + NEW_LINE +
                "};";
            HttpRequest request = request()
                .withPath("/somePath")
                .withQueryStringParameter("nameOne", "valueOne")
                .withQueryStringParameter("nameTwo", "valueTwoOne", "valueTwoTwo")
                .withMethod("POST")
                .withCookie("session", "some_session_id")
                .withBody("some_body");

            // when
            HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

            // then
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService
                        .now()
                        .getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }")
            ));
            verify(mockServerLogger).logEvent(
                new LogEntry()
                    .setType(TEMPLATE_GENERATED)
                    .setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("generated output:{}from template:{}for request:{}")
                    .setArguments(
                        OBJECT_MAPPER.readTree("" +
                                                   "{" + NEW_LINE +
                                                   "    'statusCode': 200," + NEW_LINE +
                                                   "    'body': \"{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService
                            .now()
                            .getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }\"" + NEW_LINE +
                                                   "}" + NEW_LINE),
                        JavaScriptTemplateEngine.wrapTemplate(template),
                        request
                    )
            );

        } finally {
            UUIDService.fixedUUID = originalFixedUUID;
            TimeService.fixedTime = originalFixedTime;
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithDynamicValuesRandom() {
        shouldPopulateRandomValue("rand_int", equalTo(1));
        shouldPopulateRandomValue("rand_int_10", allOf(greaterThan(0), lessThan(3)));
        shouldPopulateRandomValue("rand_int_100", allOf(greaterThan(0), lessThan(4)));
        shouldPopulateRandomValue("rand_bytes", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("rand_bytes_16", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("rand_bytes_32", allOf(greaterThan(40), lessThan(60)));
        shouldPopulateRandomValue("rand_bytes_64", allOf(greaterThan(80), lessThan(120)));
        shouldPopulateRandomValue("rand_bytes_128", allOf(greaterThan(160), lessThan(300)));
    }

    private void shouldPopulateRandomValue(String function, Matcher<Integer> matcher) {
        // given
        graalJsAvailable();
        String template = "return { 'body': " + function + " };";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), not(equalTo("")));
        assertThat(actualHttpResponse.getBodyAsString().length(), matcher);
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithLoopOverValuesUsingThis() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "var headers = '';" + NEW_LINE +
            "for (header in request.headers) {" + NEW_LINE +
            "  headers += '\\'' + request.headers[header] + '\\', ';" + NEW_LINE +
            "}" + NEW_LINE +
            "return {" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': '{\\'headers\\': [' + headers.slice(0, -2) + ']}'" + NEW_LINE +
            "};";

        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'headers': ['mock-server.com', 'plain/text']}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'headers': ['mock-server.com', 'plain/text']}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptResponseTemplateWithIfElse() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "" +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{\"name\":\"value\"}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{\\\"name\\\":\\\"value\\\"}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptForwardTemplateWithPathBodyParametersAndCookies() throws JsonProcessingException {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'path': request.path," + NEW_LINE +
            "    'body': '{\\'queryStringParameters\\': \\'' + request.queryStringParameters.nameOne[0] + ',' + request.queryStringParameters.nameTwo[0] + ',' + request.queryStringParameters.nameTwo[1] + '\\'," +
            " \\'pathParameters\\': \\'' + request.pathParameters.nameOne[0] + ',' + request.pathParameters.nameTwo[0] + ',' + request.pathParameters.nameTwo[1] + '\\'," +
            " \\'cookies\\': \\'' + request.cookies.session + '\\'," +
            " \\'body\\': \\'' + request.body + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withQueryStringParameter("nameOne", "queryValueOne")
            .withQueryStringParameter("nameTwo", "queryValueTwoOne", "queryValueTwoTwo")
            .withPathParameter("nameOne", "pathValueOne")
            .withPathParameter("nameTwo", "pathValueTwoOne", "pathValueTwoTwo")
            .withMethod("POST")
            .withCookie("session", "some_session_id")
            .withBody("some_body");

        // when
        HttpRequest actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpRequestDTO.class);

        // then
        assertThat(actualHttpRequest, is(
            request()
                .withPath("/somePath")
                .withBody(
                    "{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'path' : \"/somePath\"," + NEW_LINE +
                                               "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    JavaScriptTemplateEngine.wrapTemplate(template),
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateFirstExample() {
        // given
        graalJsAvailable();
        String template = "" +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                            .withPath("/somePath")
                                                                                                                            .withMethod("POST")
                                                                                                                            .withBody("some_body"),
                                                                                                                        HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{\"name\":\"value\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithoutDeniedClassWithoutDeniedText() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText(null);

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Cannot run program \"does_not_exist.sh\""));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedClass() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.Runtime");
            configuration.javascriptDisallowedText(null);

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            // GraalJS 25.x reports denied class lookup via dot notation as a TypeError rather than ClassNotFoundException
            assertThat(exception.getMessage(), containsString("Runtime.getRuntime is not a function"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedClassList() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.Runtime,java.lang.String");
            configuration.javascriptDisallowedText(null);

            String templateOne = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(new java.lang.String(\"does_not_exist.sh\"))" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(templateOne, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            // GraalJS 25.x reports denied class lookup via dot notation as a TypeError rather than ClassNotFoundException
            assertThat(exception.getMessage(), containsString("Runtime.getRuntime is not a function"));

            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.String,java.lang.Runtime");
            configuration.javascriptDisallowedText(null);

            String templateTwo = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': java.lang.Integer.parseInt(new java.lang.String(\"200\"))," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(new java.lang.String(\"does_not_exist.sh\"))" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(templateTwo, request()
                                                                                                                                                     .withPath("/somePath")
                                                                                                                                                     .withMethod("POST")
                                                                                                                                                     .withBody("some_body"),
                                                                                                                                                 HttpResponseDTO.class
            ));
            // GraalJS 25.x reports denied class via 'new' as: "Access to host class java.lang.String is not allowed or does not exist."
            assertThat(exception.getMessage(), containsString("Access to host class java.lang.String is not allowed or does not exist"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedText() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText("getRuntime().exec");

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"getRuntime().exec\" in template:"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedTextList() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText("getRuntime().exec,does_not_exist.sh");

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"getRuntime().exec\" in template:"));

            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses(null);
            configuration.javascriptDisallowedText("does_not_exist.sh,getRuntime().exec");

            // then
            exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                     .withPath("/somePath")
                                                                                                                                                     .withMethod("POST")
                                                                                                                                                     .withBody("some_body"),
                                                                                                                                                 HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"does_not_exist.sh\" in template:"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateWithJavaStringsWithDeniedClassAndDeniedText() {
        String originalJavaScriptRestrictedClass = configuration.javascriptDisallowedClasses();
        String originalJavaScriptRestrictedText = configuration.javascriptDisallowedText();

        try {
            // given
        graalJsAvailable();
            configuration.javascriptDisallowedClasses("java.lang.Runtime");
            configuration.javascriptDisallowedText("getRuntime().exec");

            String template = "" +
                "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 200," + NEW_LINE +
                "        'body': java.lang.Runtime.getRuntime().exec(\"does_not_exist.sh\")" + NEW_LINE +
                "    };" + NEW_LINE +
                "} else {" + NEW_LINE +
                "    return {" + NEW_LINE +
                "        'statusCode': 406," + NEW_LINE +
                "        'body': request.body" + NEW_LINE +
                "    };" + NEW_LINE +
                "}";

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                               .withPath("/somePath")
                                                                                                                                                               .withMethod("POST")
                                                                                                                                                               .withBody("some_body"),
                                                                                                                                                           HttpResponseDTO.class
            ));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"getRuntime().exec\" in template:"));

        } finally {
            configuration.javascriptDisallowedClasses(originalJavaScriptRestrictedClass);
            configuration.javascriptDisallowedText(originalJavaScriptRestrictedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithSlowJavaScriptTemplate() {
        // given
        graalJsAvailable();
        String template = "" +
            "for (var i = 0; i < 1000000000; i++) {" + NEW_LINE +
            "  i * i;" + NEW_LINE +
            "}" + NEW_LINE +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                            .withPath("/somePath")
                                                                                                                            .withMethod("POST")
                                                                                                                            .withBody("some_body"),
                                                                                                                        HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{\"name\":\"value\"}")
        ));
    }

    @Test
    public void shouldHandleMultipleHttpRequestsInParallel() throws InterruptedException {
        // given
        graalJsAvailable();
        final String template = "" +
            "for (var i = 0; i < 1000000000; i++) {" + NEW_LINE +
            "  i * i;" + NEW_LINE +
            "}" + NEW_LINE +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        final JavaScriptTemplateEngine javascriptTemplateEngine = new JavaScriptTemplateEngine(mockServerLogger, configuration);

        // then
        final HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withBody("some_body");
        Thread[] threads = new Thread[3];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Scheduler.SchedulerThreadFactory("MockServer Test " + this.getClass().getSimpleName()).newThread(() -> assertThat(javascriptTemplateEngine.executeTemplate(template, request,
                                                                                                                                                                                        HttpResponseDTO.class
            ), is(
                response()
                    .withStatusCode(200)
                    .withBody("{\"name\":\"value\"}")
            )));
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptTemplateSecondExample() {
        // given
        graalJsAvailable();
        String template = "" +
            "if (request.method === 'POST' && request.path === '/somePath') {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 200," + NEW_LINE +
            "        'body': JSON.stringify({name: 'value'})" + NEW_LINE +
            "    };" + NEW_LINE +
            "} else {" + NEW_LINE +
            "    return {" + NEW_LINE +
            "        'statusCode': 406," + NEW_LINE +
            "        'body': request.body" + NEW_LINE +
            "    };" + NEW_LINE +
            "}";

        // when
        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                            .withPath("/someOtherPath")
                                                                                                                            .withBody("some_body"),
                                                                                                                        HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(406)
                .withBody("some_body")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptForwardTemplateWithMethodPathAndHeader() {
        // given
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'path': '/somePath'," + NEW_LINE +
            "    'body': '{\\'method\\': \\'' + request.method + '\\', \\'path\\': \\'' + request.path + '\\', \\'headers\\': \\'' + request.headers.host[0] + '\\'}'" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpRequest actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpRequestDTO.class);

        // then
        assertThat(actualHttpRequest, is(
            request()
                .withPath("/somePath")
                .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithStringBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: JSON.parse(request.body).is_active, id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}"),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithJsonBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: JSON.parse(request.body).is_active, id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody(json("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}")),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":\"active_value\",\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithXmlBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: request.body, id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody(xml("<root><is_active>active_value</is_active></root>")),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":\"<root><is_active>active_value</is_active></root>\",\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithJavaScriptUsingBodyAsStringForRequestWithParameterBody() {
        // given
        graalJsAvailable();
        String template = "" +
            "return { statusCode: 200, headers: { Date: [ \"Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)\" ] }, body: JSON.stringify({is_active: JSON.parse(request.body), id: \"1234\", name: \"taras\"}) };";


        // when
        HttpResponse actualHttpRequest = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                           .withPath("/someOtherPath")
                                                                                                                           .withBody(params(param("one", "valueOne"), param("two", "valueTwoOne", "valueTwoTwo"))),
                                                                                                                       HttpResponseDTO.class
        );

        // then
        assertThat(actualHttpRequest, is(
            response()
                .withStatusCode(200)
                .withHeader("Date", "Fri Jan 28 2022 22:02:46 GMT+0000 (GMT)")
                .withBody("{\"is_active\":{\"one\":[\"valueOne\"],\"two\":[\"valueTwoOne\",\"valueTwoTwo\"]},\"id\":\"1234\",\"name\":\"taras\"}")
        ));
    }

    @Test
    public void shouldHandleInvalidJavaScript() {
        // given
        graalJsAvailable();
        String template = "{" + NEW_LINE +
            "    'path' : \"/somePath\"," + NEW_LINE +
            "    'queryStringParameters' : [ {" + NEW_LINE +
            "        'name' : \"queryParameter\"," + NEW_LINE +
            "        'values' : request.queryStringParameters['queryParameter']" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    'headers' : [ {" + NEW_LINE +
            "        'name' : \"Host\"," + NEW_LINE +
            "        'values' : [ \"localhost:1090\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    'body': \"{'name': 'value'}\"" + NEW_LINE +
            "};";
        // when
        RuntimeException runtimeException = assertThrows(RuntimeException.class, () -> new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request()
                                                                                                                                                                         .withPath("/someOtherPath")
                                                                                                                                                                         .withQueryStringParameter("queryParameter", "someValue")
                                                                                                                                                                         .withBody("some_body"),
                                                                                                                                                                     HttpRequestDTO.class
        ));

        // then - GraalJS error message differs from Nashorn but still reports a syntax error
        assertThat(runtimeException.getMessage(), allOf(
            containsString("Exception:"),
            containsString("transforming template:"),
            containsString("for request:")
        ));
    }

    @Test
    public void shouldHandleResponseTemplateWithJavaScript() {
        graalJsAvailable();
        String template = "return {" + NEW_LINE +
            "    'statusCode': response.statusCode," + NEW_LINE +
            "    'body': 'path=' + request.path + ',originalBody=' + response.body" + NEW_LINE +
            "};";
        HttpRequest request = request()
            .withPath("/testPath")
            .withMethod("GET");
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("hello");

        HttpResponse actualHttpResponse = new JavaScriptTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, httpResponse, HttpResponseDTO.class);

        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("path=/testPath,originalBody=hello")
        ));
    }

    @Test
    public void shouldRestrictGlobalContextMultipleHttpRequestsInParallel() throws InterruptedException, ExecutionException {
        // given
        graalJsAvailable();
        final String template = ""
            + "var resbody = \"ok\"; " + NEW_LINE
            + "if (request.path.match(\".*1$\")) { " + NEW_LINE
            + "    resbody = \"nok\"; " + NEW_LINE
            + "}; " + NEW_LINE
            + "resp = { " + NEW_LINE
            + "    'statusCode': 200, "
            + "    'body': resbody" + NEW_LINE
            + "}; " + NEW_LINE
            + "return resp;";

        // when
        final JavaScriptTemplateEngine javascriptTemplateEngine = new JavaScriptTemplateEngine(mockServerLogger, configuration);

        // then
        final HttpRequest ok = request()
            .withPath("/somePath/0")
            .withMethod("POST")
            .withBody("some_body");

        final HttpRequest nok = request()
            .withPath("/somePath/1")
            .withMethod("POST")
            .withBody("another_body");

        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(newFixedThreadPool.submit(() -> {
                assertThat(javascriptTemplateEngine.executeTemplate(template, ok,
                                                                    HttpResponseDTO.class
                ), is(
                    response()
                        .withStatusCode(200)
                        .withBody("ok")
                ));
                return true;
            }));

            futures.add(newFixedThreadPool.submit(() -> {
                assertThat(javascriptTemplateEngine.executeTemplate(template, nok,
                                                                    HttpResponseDTO.class
                ), is(
                    response()
                        .withStatusCode(200)
                        .withBody("nok")
                ));
                return true;
            }));

        }

        for (Future<Boolean> future : futures) {
            future.get();
        }
        newFixedThreadPool.shutdown();
    }

}
