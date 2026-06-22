package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScimShaperTest {

    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;
    private ScimShaper shaper;

    @Before
    public void setup() {
        shaper = new ScimShaper();
    }

    private ObjectNode user(String id) {
        ObjectNode user = NODE_FACTORY.objectNode();
        user.put("id", id);
        user.put("userName", "bjensen");
        return user;
    }

    @Test
    public void injectsSchemasIdAndMeta() {
        ObjectNode shaped = shaper.shapeResource(user("42"), ScimShaper.ResourceType.USER,
            "http://localhost:1080", "/scim/v2", true);

        assertThat(shaped.get("schemas").get(0).asText(), is(ScimShaper.USER_SCHEMA));
        assertThat(shaped.get("id").asText(), is("42"));
        ObjectNode meta = (ObjectNode) shaped.get("meta");
        assertThat(meta.get("resourceType").asText(), is("User"));
        assertTrue(meta.has("created"));
        assertTrue(meta.has("lastModified"));
        assertThat(meta.get("location").asText(), is("http://localhost:1080/scim/v2/Users/42"));
        assertTrue(meta.get("version").asText().startsWith("W/\""));
    }

    @Test
    public void createSetsCreatedButPutDoesNotOverwriteExistingCreated() {
        ObjectNode created = shaper.shapeResource(user("1"), ScimShaper.ResourceType.USER, "", "/scim/v2", true);
        String createdTs = created.get("meta").get("created").asText();

        // simulate a stored resource that already carries a created timestamp; PUT (created=false)
        ObjectNode stored = user("1");
        ObjectNode meta = stored.putObject("meta");
        meta.put("created", createdTs);
        ObjectNode put = shaper.shapeResource(stored, ScimShaper.ResourceType.USER, "", "/scim/v2", false);
        assertThat(put.get("meta").get("created").asText(), is(createdTs));
    }

    @Test
    public void groupLocationUsesGroupsSegment() {
        ObjectNode shaped = shaper.shapeResource(user("7"), ScimShaper.ResourceType.GROUP,
            "https://idp", "/scim/v2", false);
        assertThat(shaped.get("meta").get("location").asText(), is("https://idp/scim/v2/Groups/7"));
        assertThat(shaped.get("meta").get("resourceType").asText(), is("Group"));
    }

    @Test
    public void listResponseEnvelopeFields() {
        List<ObjectNode> resources = Arrays.asList(user("1"), user("2"));
        ObjectNode envelope = shaper.listResponse(resources, 5, 1);

        assertThat(envelope.get("schemas").get(0).asText(), is(ScimShaper.LIST_RESPONSE_SCHEMA));
        assertThat(envelope.get("totalResults").asInt(), is(5));
        assertThat(envelope.get("startIndex").asInt(), is(1));
        assertThat(envelope.get("itemsPerPage").asInt(), is(2));
        assertThat(envelope.get("Resources").size(), is(2));
    }

    @Test
    public void errorEnvelopeFields() {
        ObjectNode error = shaper.error(400, "userName is required", "invalidValue");
        assertThat(error.get("schemas").get(0).asText(), is(ScimShaper.ERROR_SCHEMA));
        assertThat(error.get("status").asText(), is("400"));
        assertThat(error.get("detail").asText(), is("userName is required"));
        assertThat(error.get("scimType").asText(), is("invalidValue"));
    }

    @Test
    public void errorEnvelopeOmitsScimTypeWhenNull() {
        ObjectNode error = shaper.error(404, "not found", null);
        assertFalse(error.has("scimType"));
        assertThat(error.get("status").asText(), is("404"));
    }
}
