package org.mockserver.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThrows;

public class ScimProviderGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ScimProviderGenerator generator;

    @Before
    public void setup() {
        ScimResourceStore.getInstance().reset();
        generator = new ScimProviderGenerator();
    }

    @After
    public void teardown() {
        ScimResourceStore.getInstance().reset();
    }

    private List<String> ids(List<Expectation> expectations) {
        return expectations.stream().map(Expectation::getId).collect(Collectors.toList());
    }

    @Test
    public void emptyConfigGeneratesAllEndpointsWithStableIds() {
        List<Expectation> expectations = generator.generate(new ScimProviderConfiguration());

        // 2 collection + 4 resource = 6 per resource type * 2 types = 12, plus 3 discovery = 15
        assertThat(expectations.size(), is(15));
        assertThat(ids(expectations), hasItems(
            "scim.scim.v2.Users.list", "scim.scim.v2.Users.create",
            "scim.scim.v2.Users.get", "scim.scim.v2.Users.put",
            "scim.scim.v2.Users.patch", "scim.scim.v2.Users.delete",
            "scim.scim.v2.Groups.list", "scim.scim.v2.Groups.create",
            "scim.scim.v2.serviceProviderConfig", "scim.scim.v2.resourceTypes", "scim.scim.v2.schemas"));
    }

    @Test
    public void reGenerateProducesSameIdsForUpsert() {
        List<String> first = ids(generator.generate(new ScimProviderConfiguration()));
        List<String> second = ids(new ScimProviderGenerator().generate(new ScimProviderConfiguration()));
        assertThat(second, is(first));
    }

    @Test
    public void initialUsersSeedTheStore() throws Exception {
        ObjectNode seedUser = (ObjectNode) mapper.readTree("{\"id\":\"u1\",\"userName\":\"seed\"}");
        ScimProviderConfiguration config = new ScimProviderConfiguration();
        List<ObjectNode> initial = new ArrayList<>();
        initial.add(seedUser);
        config.setInitialUsers(initial);

        generator.generate(config);

        ScimResourceStore.Provider provider = ScimResourceStore.getInstance().providerForBasePath("/scim/v2");
        assertThat(provider.getUsers().size(), is(1));
        assertThat(provider.getUsers().getById("u1").get("userName").asText(), is("seed"));
    }

    @Test
    public void customBasePathAppearsInPathsAndIds() {
        ScimProviderConfiguration config = new ScimProviderConfiguration().setBasePath("/api/scim");
        List<Expectation> expectations = generator.generate(config);

        boolean hasUsersListPath = expectations.stream()
            .map(e -> (org.mockserver.model.HttpRequest) e.getHttpRequest())
            .anyMatch(r -> "/api/scim/Users".equals(r.getPath().getValue())
                && "GET".equals(r.getMethod().getValue()));
        assertThat(hasUsersListPath, is(true));
        assertThat(ids(expectations), hasItem("scim.api.scim.Users.list"));
        assertThat(ScimResourceStore.getInstance().providerForBasePath("/api/scim"), is(org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    public void serviceProviderConfigReflectsEnforcementFlags() {
        ScimProviderConfiguration config = new ScimProviderConfiguration()
            .setEnforcePatch(false).setEnforceFilter(false);
        List<Expectation> expectations = generator.generate(config);

        Expectation discovery = expectations.stream()
            .filter(e -> "scim.scim.v2.serviceProviderConfig".equals(e.getId()))
            .findFirst().orElseThrow(AssertionError::new);
        HttpResponse response = discovery.getHttpResponse();
        String body = response.getBodyAsString();
        assertThat(response.getFirstHeader("content-type"), is(ScimShaper.CONTENT_TYPE));
        assertThat(body, containsString("\"patch\""));
        assertThat(body, containsString("oauthbearertoken"));
    }

    @Test
    public void basePathMustStartWithSlash() {
        assertThrows(IllegalArgumentException.class,
            () -> generator.generate(new ScimProviderConfiguration().setBasePath("scim")));
    }

    @Test
    public void basePathMustNotOverlapControlPlane() {
        assertThrows(IllegalArgumentException.class,
            () -> generator.generate(new ScimProviderConfiguration().setBasePath("/mockserver/scim")));
    }

    @Test
    public void normalizeStripsTrailingSlash() {
        assertThat(ScimProviderGenerator.normalizeBasePath("/scim/v2/"), is("/scim/v2"));
        assertThat(ScimProviderGenerator.normalizeBasePath(""), is("/scim/v2"));
        assertThat(ScimProviderGenerator.normalizeBasePath("  "), is("/scim/v2"));
    }

    // --- expectedBearerToken is WRITE_ONLY: never serialized back out, but still deserializes from inbound PUT ---

    @Test
    public void expectedBearerTokenIsNeverSerialized() throws Exception {
        ScimProviderConfiguration config = new ScimProviderConfiguration()
            .setRequireBearerToken(true)
            .setExpectedBearerToken("SUPER-SECRET-TOKEN");

        String json = new ObjectMapper().writeValueAsString(config);

        assertThat("expected bearer token must not appear in serialized JSON", json, not(containsString("SUPER-SECRET-TOKEN")));
        assertThat(json, not(containsString("expectedBearerToken")));
        // the non-secret flag is still serialized
        assertThat(json, containsString("requireBearerToken"));
    }

    @Test
    public void expectedBearerTokenStillDeserializesFromInboundPut() throws Exception {
        String inbound = "{\"requireBearerToken\":true,\"expectedBearerToken\":\"inbound-token\"}";

        ScimProviderConfiguration config = new ObjectMapper().readValue(inbound, ScimProviderConfiguration.class);

        assertThat(config.isRequireBearerToken(), is(true));
        assertThat(config.getExpectedBearerToken(), is("inbound-token"));
    }
}
