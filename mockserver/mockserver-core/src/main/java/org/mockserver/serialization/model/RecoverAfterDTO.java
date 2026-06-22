package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.model.RecoverAfter;

/**
 * Serialization DTO for {@link RecoverAfter}. Mirrors the {@code delay}/{@code connectionOptions}
 * nullable-nested pattern: a {@code null} field is omitted from the JSON (so an absent
 * {@code failResponse} or {@code idempotencyHeader} produces no key), and the whole
 * {@code recoverAfter} clause is omitted from a response that has none.
 *
 * @author jamesdbloom
 */
@SuppressWarnings("UnusedReturnValue")
public class RecoverAfterDTO extends ObjectWithJsonToString implements DTO<RecoverAfter> {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer failTimes;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private HttpResponseDTO failResponse;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String idempotencyHeader;

    public RecoverAfterDTO() {
    }

    public RecoverAfterDTO(RecoverAfter recoverAfter) {
        if (recoverAfter != null) {
            failTimes = recoverAfter.getFailTimes();
            failResponse = recoverAfter.getFailResponse() != null ? new HttpResponseDTO(recoverAfter.getFailResponse()) : null;
            idempotencyHeader = recoverAfter.getIdempotencyHeader();
        }
    }

    public RecoverAfter buildObject() {
        return new RecoverAfter()
            .withFailTimes(failTimes)
            .withFailResponse(failResponse != null ? failResponse.buildObject() : null)
            .withIdempotencyHeader(idempotencyHeader);
    }

    public Integer getFailTimes() {
        return failTimes;
    }

    public RecoverAfterDTO setFailTimes(Integer failTimes) {
        this.failTimes = failTimes;
        return this;
    }

    public HttpResponseDTO getFailResponse() {
        return failResponse;
    }

    public RecoverAfterDTO setFailResponse(HttpResponseDTO failResponse) {
        this.failResponse = failResponse;
        return this;
    }

    public String getIdempotencyHeader() {
        return idempotencyHeader;
    }

    public RecoverAfterDTO setIdempotencyHeader(String idempotencyHeader) {
        this.idempotencyHeader = idempotencyHeader;
        return this;
    }
}
