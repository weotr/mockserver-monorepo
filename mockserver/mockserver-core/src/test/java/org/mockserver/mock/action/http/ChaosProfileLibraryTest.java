package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.state.InMemoryStateBackend;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the persisted, named {@link ChaosProfileLibrary}. Uses the
 * default {@link InMemoryStateBackend} so the library is exercised against the
 * same CRUD-entity store used in single-node deployments.
 */
public class ChaosProfileLibraryTest {

    private ChaosProfileLibrary library;

    private static final String DEFINITION_JSON =
        "{\"name\":\"ignored\",\"stages\":[{\"durationMillis\":30000," +
            "\"profiles\":{\"payments.svc\":{\"errorStatusCode\":503,\"errorProbability\":0.5}}}]}";

    @Before
    public void setUp() {
        // fresh backend per test => clean isolation
        library = new ChaosProfileLibrary(new InMemoryStateBackend(1000));
    }

    private JsonNode definition() throws Exception {
        return ObjectMapperFactory.createObjectMapper().readTree(DEFINITION_JSON);
    }

    @Test
    public void shouldSaveListGetAndDeleteRoundTrip() throws Exception {
        assertThat(library.list(), is(empty()));

        library.save("payments-outage", definition());

        assertThat(library.list(), contains("payments-outage"));

        Optional<ObjectNode> stored = library.get("payments-outage");
        assertThat(stored.isPresent(), is(true));
        // the saved-under name overrides whatever name was inside the body
        assertThat(stored.get().path("name").asText(), is("payments-outage"));
        assertThat(stored.get().path("stages").size(), is(1));

        assertThat(library.delete("payments-outage"), is(true));
        assertThat(library.list(), is(empty()));
        assertThat(library.get("payments-outage").isPresent(), is(false));
    }

    @Test
    public void shouldListProfilesSortedByName() throws Exception {
        library.save("zebra", definition());
        library.save("alpha", definition());
        library.save("mike", definition());
        assertThat(library.list(), contains("alpha", "mike", "zebra"));
    }

    @Test
    public void shouldReplaceProfileOnReSave() throws Exception {
        library.save("p", definition());
        ObjectNode updated = (ObjectNode) definition();
        updated.put("loop", true);
        library.save("p", updated);
        assertThat(library.list(), contains("p"));
        assertThat(library.get("p").get().path("loop").asBoolean(), is(true));
    }

    @Test
    public void shouldReturnEmptyForUnknownProfile() {
        assertThat(library.get("nope").isPresent(), is(false));
    }

    @Test
    public void shouldReturnFalseDeletingUnknownProfile() {
        assertThat(library.delete("nope"), is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidName() throws Exception {
        library.save("bad name/with slash", definition());
    }

    @Test
    public void shouldAllowNamesWithSpaces() throws Exception {
        library.save("Payments Outage", definition());
        assertThat(library.list(), contains("Payments Outage"));
        assertThat(library.get("Payments Outage").get().path("name").asText(), is("Payments Outage"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullDefinition() {
        library.save("ok", null);
    }

    @Test
    public void shouldValidateNames() {
        assertThat(ChaosProfileLibrary.isValidName("payments-outage_1.2"), is(true));
        assertThat(ChaosProfileLibrary.isValidName("Payments Outage"), is(true));
        assertThat(ChaosProfileLibrary.isValidName("x"), is(true));
        assertThat(ChaosProfileLibrary.isValidName(""), is(false));
        assertThat(ChaosProfileLibrary.isValidName(null), is(false));
        assertThat(ChaosProfileLibrary.isValidName(" leading"), is(false));
        assertThat(ChaosProfileLibrary.isValidName("trailing "), is(false));
        assertThat(ChaosProfileLibrary.isValidName("has/slash"), is(false));
    }
}
