package org.mockserver.scim;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the SCIM 2.0 mock provider. All fields are optional with sensible defaults so
 * that {@code PUT /mockserver/scim} with an empty body produces a fully functional SCIM provider
 * serving {@code /scim/v2/Users}, {@code /scim/v2/Groups}, and the discovery endpoints
 * ({@code /ServiceProviderConfig}, {@code /ResourceTypes}, {@code /Schemas}).
 *
 * <p>Mirrors {@link org.mockserver.oidc.OidcProviderConfiguration} in shape (fluent setters,
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)}, {@code @JsonInclude(NON_NULL)},
 * {@link Serializable}) so it serializes/deserializes cleanly over the control plane.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScimProviderConfiguration implements Serializable {

    private String basePath = "/scim/v2";
    private IdStrategy idStrategy = IdStrategy.UUID;
    private List<ObjectNode> initialUsers = new ArrayList<>();
    private List<ObjectNode> initialGroups = new ArrayList<>();
    private boolean enforceFilter = true;
    private boolean enforcePatch = true;
    private boolean requireBearerToken = false;
    // Security: the expected bearer token must NEVER be serialized back out (credential leak via
    // JSON / response). WRITE_ONLY lets an inbound PUT body supply it while excluding it from
    // serialization; the typed client re-injects it on the outbound PUT so a supplied value still
    // reaches the server.
    @JsonProperty(value = "expectedBearerToken", access = JsonProperty.Access.WRITE_ONLY)
    private String expectedBearerToken = null;

    public ScimProviderConfiguration() {
    }

    @JsonProperty("basePath")
    public String getBasePath() {
        return basePath;
    }

    public ScimProviderConfiguration setBasePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    @JsonProperty("idStrategy")
    public IdStrategy getIdStrategy() {
        return idStrategy;
    }

    public ScimProviderConfiguration setIdStrategy(IdStrategy idStrategy) {
        this.idStrategy = idStrategy;
        return this;
    }

    @JsonProperty("initialUsers")
    public List<ObjectNode> getInitialUsers() {
        return initialUsers;
    }

    public ScimProviderConfiguration setInitialUsers(List<ObjectNode> initialUsers) {
        this.initialUsers = initialUsers;
        return this;
    }

    @JsonProperty("initialGroups")
    public List<ObjectNode> getInitialGroups() {
        return initialGroups;
    }

    public ScimProviderConfiguration setInitialGroups(List<ObjectNode> initialGroups) {
        this.initialGroups = initialGroups;
        return this;
    }

    @JsonProperty("enforceFilter")
    public boolean isEnforceFilter() {
        return enforceFilter;
    }

    public ScimProviderConfiguration setEnforceFilter(boolean enforceFilter) {
        this.enforceFilter = enforceFilter;
        return this;
    }

    @JsonProperty("enforcePatch")
    public boolean isEnforcePatch() {
        return enforcePatch;
    }

    public ScimProviderConfiguration setEnforcePatch(boolean enforcePatch) {
        this.enforcePatch = enforcePatch;
        return this;
    }

    /**
     * Whether the mock SCIM endpoints require an {@code Authorization: Bearer <token>} header.
     *
     * <p>Note the interaction with {@link #getExpectedBearerToken()}: when
     * {@code requireBearerToken=true} but {@code expectedBearerToken} is left unset (null/blank),
     * the gate is presence-only — <em>any</em> non-empty bearer token is accepted (the value is not
     * pinned). Set {@code expectedBearerToken} to additionally pin the token to a specific value.
     */
    @JsonProperty("requireBearerToken")
    public boolean isRequireBearerToken() {
        return requireBearerToken;
    }

    public ScimProviderConfiguration setRequireBearerToken(boolean requireBearerToken) {
        this.requireBearerToken = requireBearerToken;
        return this;
    }

    /**
     * The exact bearer token incoming SCIM requests must present when {@link #isRequireBearerToken()}
     * is enabled. When left unset (null/blank) with {@code requireBearerToken=true}, any non-empty
     * bearer token is accepted (presence-only, not value-pinned).
     *
     * <p>Serialized {@code WRITE_ONLY}: the server never echoes this credential back out, so it does
     * not leak via the control-plane response.
     */
    @JsonIgnore
    public String getExpectedBearerToken() {
        return expectedBearerToken;
    }

    public ScimProviderConfiguration setExpectedBearerToken(String expectedBearerToken) {
        this.expectedBearerToken = expectedBearerToken;
        return this;
    }
}
