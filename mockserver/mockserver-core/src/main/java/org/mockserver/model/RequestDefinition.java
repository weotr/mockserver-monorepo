package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public abstract class RequestDefinition extends Not {

    private String logCorrelationId;
    private Long receivedTimestamp;

    @JsonIgnore
    public String getLogCorrelationId() {
        return logCorrelationId;
    }

    public RequestDefinition withLogCorrelationId(String logCorrelationId) {
        this.logCorrelationId = logCorrelationId;
        return this;
    }

    /**
     * Returns the epoch-millis timestamp at which MockServer first received
     * this request, or {@code null} if not yet set.  The timestamp is
     * operational metadata — excluded from {@code equals}/{@code hashCode}
     * and from JSON serialization, just like {@code logCorrelationId}.
     */
    @JsonIgnore
    public Long getReceivedTimestamp() {
        return receivedTimestamp;
    }

    /**
     * Sets the epoch-millis receive timestamp.  Callers should use
     * {@link org.mockserver.time.EpochService#currentTimeMillis()} so that
     * frozen-clock tests are deterministic.
     */
    public RequestDefinition withReceivedTimestamp(Long receivedTimestamp) {
        this.receivedTimestamp = receivedTimestamp;
        return this;
    }

    public abstract RequestDefinition shallowClone();

    public RequestDefinition cloneWithLogCorrelationId() {
        return MockServerLogger.isEnabled(Level.TRACE) && isNotBlank(getLogCorrelationId()) ? shallowClone().withLogCorrelationId(getLogCorrelationId()) : this;
    }

    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

}
