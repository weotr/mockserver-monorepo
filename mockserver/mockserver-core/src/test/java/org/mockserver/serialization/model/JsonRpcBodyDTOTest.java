package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.Body;
import org.mockserver.model.JsonRpcBody;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class JsonRpcBodyDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        JsonRpcBody jsonRpcBody = new JsonRpcBody("tools/list");
        JsonRpcBodyDTO dto = new JsonRpcBodyDTO(jsonRpcBody);

        assertThat( dto.getMethod(), is("tools/list"));
        assertThat(dto.getParamsSchema(), nullValue());
        assertThat( dto.getType(), is(Body.Type.JSON_RPC));
    }

    @Test
    public void shouldReturnValuesWithParamsSchema() {
        String schema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        JsonRpcBody jsonRpcBody = new JsonRpcBody("tools/call", schema);
        JsonRpcBodyDTO dto = new JsonRpcBodyDTO(jsonRpcBody);

        assertThat( dto.getMethod(), is("tools/call"));
        assertThat( dto.getParamsSchema(), is(schema));
        assertThat( dto.getType(), is(Body.Type.JSON_RPC));
    }

    @Test
    public void shouldBuildObject() {
        JsonRpcBody original = new JsonRpcBody("tools/call", "{\"type\": \"object\"}");
        JsonRpcBodyDTO dto = new JsonRpcBodyDTO(original);
        JsonRpcBody rebuilt = dto.buildObject();

        assertThat( rebuilt.getMethod(), is(original.getMethod()));
        assertThat( rebuilt.getParamsSchema(), is(original.getParamsSchema()));
        assertThat( rebuilt.getType(), is(Body.Type.JSON_RPC));
    }

    @Test
    public void shouldPreserveNotFlag() {
        JsonRpcBody jsonRpcBody = new JsonRpcBody("tools/list");
        JsonRpcBodyDTO dto = new JsonRpcBodyDTO(jsonRpcBody, true);

        assertThat(dto.getNot(), is(true));
    }

    @Test
    public void shouldPreserveOptionalFlag() {
        JsonRpcBody jsonRpcBody = (JsonRpcBody) new JsonRpcBody("tools/list").withOptional(true);
        JsonRpcBodyDTO dto = new JsonRpcBodyDTO(jsonRpcBody);

        assertThat(dto.getOptional(), is(true));
        assertThat(dto.buildObject().getOptional(), is(true));
    }
}
