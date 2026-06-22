package org.mockserver.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ScimPatchApplierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ScimPatchApplier applier;

    @Before
    public void setup() {
        applier = new ScimPatchApplier();
    }

    private ObjectNode user() throws Exception {
        return (ObjectNode) mapper.readTree(
            "{\"id\":\"1\",\"userName\":\"bjensen\",\"active\":true," +
                "\"name\":{\"givenName\":\"Barbara\",\"familyName\":\"Jensen\"}," +
                "\"emails\":[{\"value\":\"a@x.com\",\"type\":\"work\"}]}");
    }

    private ObjectNode userWithEnvelope() throws Exception {
        return (ObjectNode) mapper.readTree(
            "{\"id\":\"1\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]," +
                "\"meta\":{\"resourceType\":\"User\",\"created\":\"2020-01-01T00:00:00Z\"}," +
                "\"userName\":\"bjensen\",\"active\":true}");
    }

    private ObjectNode patchOp(String operations) throws Exception {
        return (ObjectNode) mapper.readTree(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + operations + "}");
    }

    @Test
    public void replaceWithPathSetsScalar() throws Exception {
        ObjectNode result = applier.apply(user(), patchOp("[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]"));
        assertFalse(result.get("active").asBoolean());
    }

    @Test
    public void replaceWithoutPathMerges() throws Exception {
        ObjectNode result = applier.apply(user(), patchOp("[{\"op\":\"replace\",\"value\":{\"active\":false,\"title\":\"Boss\"}}]"));
        assertFalse(result.get("active").asBoolean());
        assertThat(result.get("title").asText(), is("Boss"));
        // unchanged attributes preserved
        assertThat(result.get("userName").asText(), is("bjensen"));
    }

    @Test
    public void replaceSubAttribute() throws Exception {
        ObjectNode result = applier.apply(user(), patchOp("[{\"op\":\"replace\",\"path\":\"name.familyName\",\"value\":\"Smith\"}]"));
        assertThat(result.get("name").get("familyName").asText(), is("Smith"));
        assertThat(result.get("name").get("givenName").asText(), is("Barbara"));
    }

    @Test
    public void addScalarSetsValue() throws Exception {
        ObjectNode result = applier.apply(user(), patchOp("[{\"op\":\"add\",\"path\":\"displayName\",\"value\":\"Barbara J\"}]"));
        assertThat(result.get("displayName").asText(), is("Barbara J"));
    }

    @Test
    public void addToArrayAppends() throws Exception {
        ObjectNode result = applier.apply(user(),
            patchOp("[{\"op\":\"add\",\"path\":\"emails\",\"value\":{\"value\":\"b@x.com\",\"type\":\"home\"}}]"));
        assertThat(result.get("emails").size(), is(2));
        assertThat(result.get("emails").get(1).get("value").asText(), is("b@x.com"));
    }

    @Test
    public void removePathDeletesAttribute() throws Exception {
        ObjectNode result = applier.apply(user(), patchOp("[{\"op\":\"remove\",\"path\":\"emails\"}]"));
        assertFalse(result.has("emails"));
    }

    @Test
    public void removeSubAttribute() throws Exception {
        ObjectNode result = applier.apply(user(), patchOp("[{\"op\":\"remove\",\"path\":\"name.familyName\"}]"));
        assertFalse(result.get("name").has("familyName"));
        assertTrue(result.get("name").has("givenName"));
    }

    @Test
    public void malformedMissingOperationsThrowsInvalidSyntax() throws Exception {
        ScimPatchApplier.ScimPatchException ex = assertThrows(ScimPatchApplier.ScimPatchException.class,
            () -> applier.apply(user(), (ObjectNode) mapper.readTree("{\"schemas\":[]}")));
        assertThat(ex.getScimType(), is("invalidSyntax"));
    }

    @Test
    public void malformedUnknownOpThrows() throws Exception {
        assertThrows(ScimPatchApplier.ScimPatchException.class,
            () -> applier.apply(user(), patchOp("[{\"op\":\"frob\",\"path\":\"active\",\"value\":1}]")));
    }

    @Test
    public void removeWithoutPathThrows() throws Exception {
        assertThrows(ScimPatchApplier.ScimPatchException.class,
            () -> applier.apply(user(), patchOp("[{\"op\":\"remove\"}]")));
    }

    @Test
    public void replaceReservedMetaPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"replace\",\"path\":\"meta\",\"value\":{\"created\":\"1999-12-31T00:00:00Z\"}}]"));
        // server-managed metadata is unchanged
        assertThat(result.get("meta").get("resourceType").asText(), is("User"));
        assertThat(result.get("meta").get("created").asText(), is("2020-01-01T00:00:00Z"));
    }

    @Test
    public void replaceReservedSchemasPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"replace\",\"path\":\"schemas\",\"value\":[\"urn:evil\"]}]"));
        // the SCIM envelope is unchanged
        assertThat(result.get("schemas").size(), is(1));
        assertThat(result.get("schemas").get(0).asText(), is("urn:ietf:params:scim:schemas:core:2.0:User"));
    }

    @Test
    public void replaceReservedIdPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"replace\",\"path\":\"id\",\"value\":\"hijacked\"}]"));
        assertThat(result.get("id").asText(), is("1"));
    }

    @Test
    public void addReservedSchemasPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"add\",\"path\":\"schemas\",\"value\":\"urn:evil\"}]"));
        // no append onto the readOnly schemas array
        assertThat(result.get("schemas").size(), is(1));
        assertThat(result.get("schemas").get(0).asText(), is("urn:ietf:params:scim:schemas:core:2.0:User"));
    }

    @Test
    public void addReservedMetaSubAttributePathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"add\",\"path\":\"meta.created\",\"value\":\"1999-12-31T00:00:00Z\"}]"));
        assertThat(result.get("meta").get("created").asText(), is("2020-01-01T00:00:00Z"));
    }

    @Test
    public void removeReservedMetaPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"remove\",\"path\":\"meta\"}]"));
        assertTrue(result.has("meta"));
        assertThat(result.get("meta").get("resourceType").asText(), is("User"));
    }

    @Test
    public void removeReservedSchemasPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"remove\",\"path\":\"schemas\"}]"));
        assertTrue(result.has("schemas"));
        assertThat(result.get("schemas").size(), is(1));
    }

    @Test
    public void removeReservedIdPathIsNoOp() throws Exception {
        ObjectNode result = applier.apply(userWithEnvelope(),
            patchOp("[{\"op\":\"remove\",\"path\":\"id\"}]"));
        assertThat(result.get("id").asText(), is("1"));
    }

    @Test
    public void originalResourceNotMutated() throws Exception {
        ObjectNode original = user();
        applier.apply(original, patchOp("[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]"));
        assertTrue(original.get("active").asBoolean());
    }
}
