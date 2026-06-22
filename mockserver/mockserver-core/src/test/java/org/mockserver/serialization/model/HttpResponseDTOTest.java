package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.*;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.StringBody.exact;

/**
 * @author jamesdbloom
 */
public class HttpResponseDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // given
        BodyDTO body = BodyDTO.createDTO(exact("body"));
        Cookies cookies = new Cookies().withEntries(cookie("name", "value"));
        Headers headers = new Headers().withEntries(header("name", "value"));
        Integer statusCode = 200;
        String randomPhrase = "randomPhrase";
        ConnectionOptionsDTO connectionOptions = new ConnectionOptionsDTO().setContentLengthHeaderOverride(50);

        HttpResponse httpResponse = new HttpResponse()
            .withBody("body")
            .withCookies(new Cookie("name", "value"))
            .withHeaders(new Header("name", "value"))
            .withStatusCode(statusCode)
            .withReasonPhrase(randomPhrase)
            .withConnectionOptions(new ConnectionOptions().withContentLengthHeaderOverride(50));

        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(httpResponse);

        // then
        assertThat(httpResponseDTO.getBody(), is(body));
        assertThat(httpResponseDTO.getCookies(), is(cookies));
        assertThat(httpResponseDTO.getHeaders(), is(headers));
        assertThat(httpResponseDTO.getStatusCode(), is(statusCode));
        assertThat(httpResponseDTO.getReasonPhrase(), is(randomPhrase));
        assertThat(httpResponseDTO.getConnectionOptions(), is(connectionOptions));
    }

    @Test
    public void shouldBuildObject() {
        // given
        String body = "body";
        Cookie cookie = new Cookie("name", "value");
        Header header = new Header("name", "value");
        Integer statusCode = 200;
        String randomPhrase = "randomPhrase";
        ConnectionOptions connectionOptions = new ConnectionOptions().withContentLengthHeaderOverride(50);

        HttpResponse httpResponse = new HttpResponse()
            .withBody(body)
            .withCookies(cookie)
            .withHeaders(header)
            .withStatusCode(statusCode)
            .withReasonPhrase(randomPhrase)
            .withConnectionOptions(connectionOptions);

        // when
        HttpResponse builtHttpResponse = new HttpResponseDTO(httpResponse).buildObject();

        // then
        assertThat(builtHttpResponse.getBody(), is(exact(body)));
        assertThat(builtHttpResponse.getCookieList(), containsInAnyOrder(cookie));
        assertThat(builtHttpResponse.getHeaderList(), containsInAnyOrder(header));
        assertThat(builtHttpResponse.getStatusCode(), is(statusCode));
        assertThat(builtHttpResponse.getReasonPhrase(), is(randomPhrase));
        assertThat(builtHttpResponse.getConnectionOptions(), is(connectionOptions));
    }

    @Test
    public void shouldReturnValuesSetInSetter() {
        // given
        BodyWithContentTypeDTO body = BodyWithContentTypeDTO.createWithContentTypeDTO(exact("body"));
        Cookies cookies = new Cookies().withEntries(cookie("name", "value"));
        Headers headers = new Headers().withEntries(header("name", "value"));
        Integer statusCode = 200;
        String randomPhrase = "randomPhrase";
        ConnectionOptionsDTO connectionOptions = new ConnectionOptionsDTO().setContentLengthHeaderOverride(50);

        HttpResponse httpResponse = new HttpResponse();

        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(httpResponse);
        httpResponseDTO.setBody(body);
        httpResponseDTO.setCookies(cookies);
        httpResponseDTO.setHeaders(headers);
        httpResponseDTO.setStatusCode(statusCode);
        httpResponseDTO.setReasonPhrase(randomPhrase);
        httpResponseDTO.setConnectionOptions(connectionOptions);

        // then
        assertThat(httpResponseDTO.getBody(), is(body));
        assertThat(httpResponseDTO.getCookies(), is(cookies));
        assertThat(httpResponseDTO.getHeaders(), is(headers));
        assertThat(httpResponseDTO.getStatusCode(), is(statusCode));
        assertThat(httpResponseDTO.getReasonPhrase(), is(randomPhrase));
        assertThat(httpResponseDTO.getConnectionOptions(), is(connectionOptions));
    }


    @Test
    public void shouldHandleNullObjectInput() {
        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(null);

        // then
        assertThat(httpResponseDTO.getBody(), is(nullValue()));
        assertThat(httpResponseDTO.getCookies(), is(nullValue()));
        assertThat(httpResponseDTO.getHeaders(), is(nullValue()));
        assertThat(httpResponseDTO.getStatusCode(), is(nullValue()));
        assertThat(httpResponseDTO.getReasonPhrase(), is(nullValue()));
        assertThat(httpResponseDTO.getConnectionOptions(), is(nullValue()));
    }

    @Test
    public void shouldHandleNullFieldInput() {
        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(new HttpResponse());

        // then
        assertThat(httpResponseDTO.getBody(), is(nullValue()));
        assertThat(httpResponseDTO.getCookies(), is(nullValue()));
        assertThat(httpResponseDTO.getHeaders(), is(nullValue()));
        assertThat(httpResponseDTO.getStatusCode(), is(nullValue()));
        assertThat(httpResponseDTO.getReasonPhrase(), is(nullValue()));
        assertThat(httpResponseDTO.getConnectionOptions(), is(nullValue()));
    }

    @Test
    public void shouldRoundTripTrailers() {
        // given
        Headers trailers = new Headers().withEntries(header("x-checksum", "abc123"));
        HttpResponse httpResponse = new HttpResponse()
            .withStatusCode(200)
            .withTrailer("x-checksum", "abc123");

        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(httpResponse);

        // then DTO captures the trailers
        assertThat(httpResponseDTO.getTrailers(), is(trailers));

        // and building back yields an equal response with the trailers preserved
        HttpResponse rebuilt = httpResponseDTO.buildObject();
        assertThat(rebuilt.getTrailerList(), containsInAnyOrder(new Header("x-checksum", "abc123")));
        assertThat(rebuilt, is(httpResponse));
    }

    @Test
    public void shouldOmitTrailersWhenAbsent() {
        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(new HttpResponse().withStatusCode(200));

        // then
        assertThat(httpResponseDTO.getTrailers(), is(nullValue()));
        assertThat(httpResponseDTO.buildObject().getTrailers(), is(nullValue()));
    }

    @Test
    public void shouldRoundTripRecoverAfter() {
        // given
        RecoverAfter recoverAfter = new RecoverAfter()
            .withFailTimes(3)
            .withFailResponse(new HttpResponse().withStatusCode(503).withHeader("Retry-After", "1"))
            .withIdempotencyHeader("Idempotency-Key");
        HttpResponse httpResponse = new HttpResponse()
            .withStatusCode(200)
            .withBody("ok")
            .withRecoverAfter(recoverAfter);

        // when
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(httpResponse);

        // then the DTO captures it
        assertThat(httpResponseDTO.getRecoverAfter(), is(new RecoverAfterDTO(recoverAfter)));

        // and building back yields an equal response with the recoverAfter preserved
        HttpResponse rebuilt = httpResponseDTO.buildObject();
        assertThat(rebuilt.getRecoverAfter(), is(recoverAfter));
        assertThat(rebuilt, is(httpResponse));
    }

    @Test
    public void shouldOmitRecoverAfterWhenAbsent() {
        // when - a response without recoverAfter
        HttpResponseDTO httpResponseDTO = new HttpResponseDTO(new HttpResponse().withStatusCode(200));

        // then the DTO field stays null and rebuilding keeps it null (so the JSON key is omitted)
        assertThat(httpResponseDTO.getRecoverAfter(), is(nullValue()));
        assertThat(httpResponseDTO.buildObject().getRecoverAfter(), is(nullValue()));
    }

    @Test
    public void plainResponseJsonHasNoRecoverAfterKey() {
        // a response WITHOUT recoverAfter must serialize byte-for-byte as before (no recoverAfter key)
        org.mockserver.serialization.HttpResponseSerializer serializer =
            new org.mockserver.serialization.HttpResponseSerializer(new org.mockserver.logging.MockServerLogger());
        String json = serializer.serialize(new HttpResponse().withStatusCode(200).withBody("ok"));
        assertThat("plain response JSON must not contain a recoverAfter key", json.contains("recoverAfter"), is(false));
    }

    @Test
    public void recoverAfterJsonRoundTrips() {
        org.mockserver.serialization.HttpResponseSerializer serializer =
            new org.mockserver.serialization.HttpResponseSerializer(new org.mockserver.logging.MockServerLogger());
        HttpResponse original = new HttpResponse()
            .withStatusCode(200)
            .withBody("ok")
            .withRecoverAfter(new RecoverAfter()
                .withFailTimes(3)
                .withIdempotencyHeader("Idempotency-Key"));

        String json = serializer.serialize(original);
        assertThat(json.contains("recoverAfter"), is(true));
        assertThat(json.contains("failTimes"), is(true));

        HttpResponse rebuilt = serializer.deserialize(json);
        assertThat(rebuilt.getRecoverAfter(), is(original.getRecoverAfter()));
    }
}
