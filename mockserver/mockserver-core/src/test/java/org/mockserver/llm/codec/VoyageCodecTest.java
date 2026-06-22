package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.RerankResponse;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class VoyageCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final VoyageCodec codec = new VoyageCodec();

    @Test
    public void shouldReturnVoyageProviderAndVersion() {
        assertThat(codec.provider(), is(Provider.VOYAGE));
        assertThat(codec.apiVersion(), is("voyage-rerank-2"));
    }

    @Test
    public void shouldEncodeRerankDataShape() throws Exception {
        // given
        List<String> documents = Arrays.asList("first", "second");

        // when — Voyage /v1/rerank shape: {"object":"list","data":[...],"usage":{...}}
        HttpResponse response = codec.encodeRerank(
            RerankResponse.rerank().withDeterministicFromInput(true).withSeed(1L), documents);

        // then
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("object").asText(), is("list"));
        assertThat(root.get("model").asText(), is("rerank-2"));
        assertThat(root.has("results"), is(false));
        JsonNode data = root.get("data");
        assertThat(data.isArray(), is(true));
        assertThat(data.size(), is(2));
        for (JsonNode result : data) {
            assertThat(result.has("index"), is(true));
            assertThat(result.has("relevance_score"), is(true));
        }
        assertThat(root.get("usage").has("total_tokens"), is(true));
    }
}
