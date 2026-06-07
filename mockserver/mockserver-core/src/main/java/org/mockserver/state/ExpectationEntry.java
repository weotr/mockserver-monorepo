package org.mockserver.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.ExpectationDTO;

import java.io.*;

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

    private static final long serialVersionUID = 1L;
    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    // Transient because Expectation is not Serializable — custom
    // readObject/writeObject handles it via JSON.
    private transient Expectation expectation;
    private final int priority;
    private final long created;
    private final String id;

    public ExpectationEntry(Expectation expectation) {
        this.expectation = expectation;
        this.id = expectation.getId();
        this.priority = expectation.getPriority();
        this.created = expectation.getCreated();
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

    @Override
    public String toString() {
        return "ExpectationEntry{id='" + id + "', priority=" + priority + ", created=" + created + '}';
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
