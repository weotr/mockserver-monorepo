package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.Cookies;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * @author jamesdbloom
 */
public class HttpResponseDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpResponse> {
    private Integer statusCode;
    private String statusCodeRange;
    private String reasonPhrase;
    private BodyWithContentTypeDTO body;
    private String generateFromSchema;
    private Cookies cookies;
    private Headers headers;
    private Headers trailers;
    private DelayDTO delay;
    private ConnectionOptionsDTO connectionOptions;
    private RecoverAfterDTO recoverAfter;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean primary;

    public HttpResponseDTO() {
    }

    public HttpResponseDTO(HttpResponse httpResponse) {
        if (httpResponse != null) {
            statusCode = httpResponse.getStatusCode();
            statusCodeRange = httpResponse.getStatusCodeRange();
            reasonPhrase = httpResponse.getReasonPhrase();
            body = BodyWithContentTypeDTO.createWithContentTypeDTO(httpResponse.getBody());
            generateFromSchema = httpResponse.getGenerateFromSchema();
            headers = httpResponse.getHeaders();
            trailers = httpResponse.getTrailers();
            cookies = httpResponse.getCookies();
            delay = (httpResponse.getDelay() != null ? new DelayDTO(httpResponse.getDelay()) : null);
            connectionOptions = (httpResponse.getConnectionOptions() != null ? new ConnectionOptionsDTO(httpResponse.getConnectionOptions()) : null);
            recoverAfter = (httpResponse.getRecoverAfter() != null ? new RecoverAfterDTO(httpResponse.getRecoverAfter()) : null);
            primary = httpResponse.isPrimary();
        }
    }

    public HttpResponse buildObject() {
        return new HttpResponse()
            .withStatusCode(statusCode)
            .withStatusCodeRange(statusCodeRange)
            .withReasonPhrase(reasonPhrase)
            .withBody(body != null ? body.buildObject() : null)
            .withGenerateFromSchema(generateFromSchema)
            .withHeaders(headers)
            .withTrailers(trailers)
            .withCookies(cookies)
            .withDelay((delay != null ? delay.buildObject() : null))
            .withConnectionOptions(connectionOptions != null ? connectionOptions.buildObject() : null)
            .withRecoverAfter(recoverAfter != null ? recoverAfter.buildObject() : null)
            .withPrimary(primary);
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public HttpResponseDTO setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public String getStatusCodeRange() {
        return statusCodeRange;
    }

    public HttpResponseDTO setStatusCodeRange(String statusCodeRange) {
        this.statusCodeRange = statusCodeRange;
        return this;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public HttpResponseDTO setReasonPhrase(String reasonPhrase) {
        this.reasonPhrase = reasonPhrase;
        return this;
    }

    public BodyWithContentTypeDTO getBody() {
        return body;
    }

    public HttpResponseDTO setBody(BodyWithContentTypeDTO body) {
        this.body = body;
        return this;
    }

    public String getGenerateFromSchema() {
        return generateFromSchema;
    }

    public HttpResponseDTO setGenerateFromSchema(String generateFromSchema) {
        this.generateFromSchema = generateFromSchema;
        return this;
    }

    public Headers getHeaders() {
        return headers;
    }

    public HttpResponseDTO setHeaders(Headers headers) {
        this.headers = headers;
        return this;
    }

    public Headers getTrailers() {
        return trailers;
    }

    public HttpResponseDTO setTrailers(Headers trailers) {
        this.trailers = trailers;
        return this;
    }

    public Cookies getCookies() {
        return cookies;
    }

    public HttpResponseDTO setCookies(Cookies cookies) {
        this.cookies = cookies;
        return this;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public HttpResponseDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public ConnectionOptionsDTO getConnectionOptions() {
        return connectionOptions;
    }

    public HttpResponseDTO setConnectionOptions(ConnectionOptionsDTO connectionOptions) {
        this.connectionOptions = connectionOptions;
        return this;
    }

    public RecoverAfterDTO getRecoverAfter() {
        return recoverAfter;
    }

    public HttpResponseDTO setRecoverAfter(RecoverAfterDTO recoverAfter) {
        this.recoverAfter = recoverAfter;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    public HttpResponseDTO setPrimary(boolean primary) {
        this.primary = primary;
        return this;
    }
}
