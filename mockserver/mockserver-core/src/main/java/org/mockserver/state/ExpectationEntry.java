package org.mockserver.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.ExpectationDTO;

import java.io.*;
import java.util.Objects;

/**
 * Serializable entry stored in the expectation {@link KeyValueStore}.
 * Contains the {@link Expectation} definition plus the three sort fields
 * (priority, created, id) that determine matching order. The live
 * {@code HttpRequestMatcher} is NOT part of this entry — each node builds
 * it lazily and caches it node-locally.
 * <p>
 * Implements {@link Serializable} with custom serialization: the
 * {@link Expectation} is serialized as its JSON string (via Jackson
 * ObjectMapper and {@link ExpectationDTO}) because the Expectation
 * domain model classes do not implement {@code Serializable}. This keeps
 * the wire format safe and avoids requiring Serializable on the entire
 * domain graph.
 */
public final class ExpectationEntry implements Serializable {

    private static final long serialVersionUID = 2L;
    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    // Transient because Expectation is not Serializable — custom
    // readObject/writeObject handles it via JSON.
    private transient Expectation expectation;
    private final int priority;
    private final long created;
    private final String id;

    /**
     * Shared remaining-times counter for clustered Times consumption.
     * <p>
     * When the expectation has limited Times (e.g. {@code Times.exactly(N)}),
     * this field starts at {@code N} and is atomically decremented via
     * backend CAS on each match across the fleet. {@code -1} means the
     * expectation has unlimited Times (fast path — no CAS needed).
     * <p>
     * This field is part of the serialized entry and replicated across
     * cluster nodes. It is the single source of truth for remaining
     * matches under clustering; the node-local {@code Times} object on
     * the {@code Expectation} is NOT authoritative in clustered mode.
     */
    private int remainingTimes;

    public ExpectationEntry(Expectation expectation) {
        this.expectation = expectation;
        this.id = expectation.getId();
        this.priority = expectation.getPriority();
        this.created = expectation.getCreated();
        this.remainingTimes = expectation.getTimes() != null && !expectation.getTimes().isUnlimited()
            ? expectation.getTimes().getRemainingTimes()
            : -1;
    }

    /**
     * Copy constructor for CAS updates: creates a new entry with an
     * updated {@code remainingTimes} counter while preserving all other
     * fields. The expectation reference is shared (not cloned) because
     * the entry is only used for the backend CAS swap; node-local
     * matchers hold their own copy.
     *
     * @param source         the source entry to copy from
     * @param remainingTimes the new remaining-times value
     */
    public ExpectationEntry(ExpectationEntry source, int remainingTimes) {
        this.expectation = source.expectation;
        this.id = source.id;
        this.priority = source.priority;
        this.created = source.created;
        this.remainingTimes = remainingTimes;
    }

    public Expectation getExpectation() {
        return expectation;
    }

    public int getPriority() {
        return priority;
    }

    public long getCreated() {
        return created;
    }

    public String getId() {
        return id;
    }

    /**
     * Returns the shared remaining-times counter. {@code -1} indicates
     * unlimited Times (no CAS needed). {@code 0} or positive indicates
     * the number of remaining matches available for consumption across
     * the cluster.
     */
    public int getRemainingTimes() {
        return remainingTimes;
    }

    /**
     * Returns whether this entry's expectation has unlimited Times.
     * Equivalent to {@code getRemainingTimes() == -1}.
     */
    public boolean isTimesUnlimited() {
        return remainingTimes == -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExpectationEntry that = (ExpectationEntry) o;
        return priority == that.priority
            && created == that.created
            && remainingTimes == that.remainingTimes
            && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, priority, created, remainingTimes);
    }

    @Override
    public String toString() {
        return "ExpectationEntry{id='" + id + "', priority=" + priority
            + ", created=" + created + ", remainingTimes=" + remainingTimes + '}';
    }

    // --- Custom serialization: Expectation as JSON string ---

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        // Serialize the Expectation as its JSON string via ExpectationDTO
        String json = OBJECT_WRITER.writeValueAsString(new ExpectationDTO(expectation));
        out.writeUTF(json);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // Deserialize the Expectation from its JSON string via ExpectationDTO
        String json = in.readUTF();
        ExpectationDTO dto = OBJECT_MAPPER.readValue(json, ExpectationDTO.class);
        this.expectation = dto.buildObject();
        // Restore the created timestamp (the DTO may not preserve it)
        if (this.expectation != null) {
            this.expectation.withCreated(this.created);
        }
    }
}
