package org.mockserver.oidc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the OIDC mock identity provider. All fields are optional with
 * sensible defaults so that {@code PUT /mockserver/oidc} with an empty body produces
 * a fully functional OIDC discovery document, JWKS endpoint, token endpoint, userinfo
 * endpoint, introspection endpoint, and revocation endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OidcProviderConfiguration implements Serializable {

    private String issuer = "http://localhost:1080";
    private String jwksPath = "/.well-known/jwks.json";
    private String tokenPath = "/token";
    private String authorizePath = "/authorize";
    private String userinfoPath = "/userinfo";
    private String introspectPath = "/introspect";
    private String revokePath = "/revoke";

    private String subject = "mock-user";
    private String clientId = "mock-client";
    private String audience = "mock-audience";
    private List<String> scopes = Arrays.asList("openid", "profile", "email");
    private int tokenExpirySeconds = 3600;
    private Map<String, Serializable> additionalClaims = new LinkedHashMap<>();

    // Negative-testing flags
    private boolean issueExpiredToken = false;
    private boolean wrongIssuer = false;
    private boolean tamperedSignature = false;

    public OidcProviderConfiguration() {
    }

    @JsonProperty("issuer")
    public String getIssuer() {
        return issuer;
    }

    public OidcProviderConfiguration setIssuer(String issuer) {
        this.issuer = issuer;
        return this;
    }

    @JsonProperty("jwksPath")
    public String getJwksPath() {
        return jwksPath;
    }

    public OidcProviderConfiguration setJwksPath(String jwksPath) {
        this.jwksPath = jwksPath;
        return this;
    }

    @JsonProperty("tokenPath")
    public String getTokenPath() {
        return tokenPath;
    }

    public OidcProviderConfiguration setTokenPath(String tokenPath) {
        this.tokenPath = tokenPath;
        return this;
    }

    @JsonProperty("authorizePath")
    public String getAuthorizePath() {
        return authorizePath;
    }

    public OidcProviderConfiguration setAuthorizePath(String authorizePath) {
        this.authorizePath = authorizePath;
        return this;
    }

    @JsonProperty("userinfoPath")
    public String getUserinfoPath() {
        return userinfoPath;
    }

    public OidcProviderConfiguration setUserinfoPath(String userinfoPath) {
        this.userinfoPath = userinfoPath;
        return this;
    }

    @JsonProperty("introspectPath")
    public String getIntrospectPath() {
        return introspectPath;
    }

    public OidcProviderConfiguration setIntrospectPath(String introspectPath) {
        this.introspectPath = introspectPath;
        return this;
    }

    @JsonProperty("revokePath")
    public String getRevokePath() {
        return revokePath;
    }

    public OidcProviderConfiguration setRevokePath(String revokePath) {
        this.revokePath = revokePath;
        return this;
    }

    @JsonProperty("subject")
    public String getSubject() {
        return subject;
    }

    public OidcProviderConfiguration setSubject(String subject) {
        this.subject = subject;
        return this;
    }

    @JsonProperty("clientId")
    public String getClientId() {
        return clientId;
    }

    public OidcProviderConfiguration setClientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    @JsonProperty("audience")
    public String getAudience() {
        return audience;
    }

    public OidcProviderConfiguration setAudience(String audience) {
        this.audience = audience;
        return this;
    }

    @JsonProperty("scopes")
    public List<String> getScopes() {
        return scopes;
    }

    public OidcProviderConfiguration setScopes(List<String> scopes) {
        this.scopes = scopes;
        return this;
    }

    @JsonProperty("tokenExpirySeconds")
    public int getTokenExpirySeconds() {
        return tokenExpirySeconds;
    }

    public OidcProviderConfiguration setTokenExpirySeconds(int tokenExpirySeconds) {
        this.tokenExpirySeconds = tokenExpirySeconds;
        return this;
    }

    @JsonProperty("additionalClaims")
    public Map<String, Serializable> getAdditionalClaims() {
        return additionalClaims;
    }

    public OidcProviderConfiguration setAdditionalClaims(Map<String, Serializable> additionalClaims) {
        this.additionalClaims = additionalClaims;
        return this;
    }

    @JsonProperty("issueExpiredToken")
    public boolean isIssueExpiredToken() {
        return issueExpiredToken;
    }

    public OidcProviderConfiguration setIssueExpiredToken(boolean issueExpiredToken) {
        this.issueExpiredToken = issueExpiredToken;
        return this;
    }

    @JsonProperty("wrongIssuer")
    public boolean isWrongIssuer() {
        return wrongIssuer;
    }

    public OidcProviderConfiguration setWrongIssuer(boolean wrongIssuer) {
        this.wrongIssuer = wrongIssuer;
        return this;
    }

    @JsonProperty("tamperedSignature")
    public boolean isTamperedSignature() {
        return tamperedSignature;
    }

    public OidcProviderConfiguration setTamperedSignature(boolean tamperedSignature) {
        this.tamperedSignature = tamperedSignature;
        return this;
    }
}
