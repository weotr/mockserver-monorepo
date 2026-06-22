package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.model.SloCriteriaDTO;
import org.mockserver.slo.SloCriteria;
import org.mockserver.slo.SloVerdict;
import org.slf4j.event.Level;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.character.Character.NEW_LINE;

/**
 * Serializer for the {@code PUT /mockserver/verifySLO} endpoint. Mirrors
 * {@link VerificationSequenceSerializer} but parses leniently with a plain
 * Jackson {@link ObjectMapper} — there is no JSON schema for {@link SloCriteria}
 * (the v1 control-plane contract is small and the DTO tolerates absent fields),
 * so the body is deserialized straight into a {@link SloCriteriaDTO} and built
 * into the model via {@link SloCriteriaDTO#buildObject()}.
 *
 * <p>The {@link #serialize(SloVerdict)} side writes the verdict response. Because
 * {@link SloVerdict} extends {@code ObjectWithJsonToString} it serializes cleanly
 * via the shared {@link ObjectMapperFactory} writer, so no dedicated verdict DTO
 * is required.
 */
@SuppressWarnings("FieldMayBeFinal")
public class SloCriteriaSerializer {

    private final MockServerLogger mockServerLogger;
    private ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);
    private ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    public SloCriteriaSerializer(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public SloCriteria deserialize(String jsonSloCriteria) {
        if (isBlank(jsonSloCriteria)) {
            throw new IllegalArgumentException(
                "1 error:" + NEW_LINE +
                    " - an SLO criteria is required but value was \"" + jsonSloCriteria + "\""
            );
        }
        try {
            SloCriteriaDTO criteriaDTO = objectMapper.readValue(jsonSloCriteria, SloCriteriaDTO.class);
            return criteriaDTO != null ? criteriaDTO.buildObject() : null;
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while parsing{}for SloCriteria " + throwable.getMessage())
                    .setArguments(jsonSloCriteria)
                    .setThrowable(throwable)
            );
            throw new IllegalArgumentException("exception while parsing [" + jsonSloCriteria + "] for SloCriteria", throwable);
        }
    }

    public String serialize(SloVerdict sloVerdict) {
        try {
            return objectWriter.writeValueAsString(sloVerdict);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception while serializing sloVerdict to JSON with value " + sloVerdict)
                    .setThrowable(e)
            );
            throw new RuntimeException("Exception while serializing sloVerdict to JSON with value " + sloVerdict, e);
        }
    }

}
