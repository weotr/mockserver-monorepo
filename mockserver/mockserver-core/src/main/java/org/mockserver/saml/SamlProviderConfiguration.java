package org.mockserver.saml;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the SAML 2.0 mock identity provider. All fields are optional with
 * sensible defaults so that {@code PUT /mockserver/saml} with an empty body produces a
 * fully functional mock IdP: a metadata endpoint and an SSO endpoint implementing the
 * SP-initiated Web-Browser-SSO POST profile.
 *
 * <p>If {@link #signingCertificatePem} / {@link #signingPrivateKeyPem} are not supplied a
 * self-signed RSA signing credential is generated, and its certificate is published in the
 * IdP metadata so the relying party (SP) can validate the XML signature.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SamlProviderConfiguration implements Serializable {

    private String idpEntityId = "http://localhost:1080/saml/idp";
    private String ssoServiceUrl = "/saml/sso";
    private String metadataUrl = "/saml/metadata";

    private String spEntityId = "http://localhost:8080/saml/sp";
    private String assertionConsumerServiceUrl = "http://localhost:8080/saml/acs";

    // Single Logout (SLO): the IdP-side endpoint path published in metadata and matched by the mock,
    // and the SP-side SLO endpoint the signed LogoutResponse is form-POSTed back to.
    private String sloServiceUrl = "/saml/logout";
    private String spSingleLogoutServiceUrl = "http://localhost:8080/saml/slo";

    private String subjectNameId = "mock-user@example.com";
    private String nameIdFormat = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";
    private Map<String, String> attributes = new LinkedHashMap<>();

    private long sessionDurationSeconds = 3600;

    // Optional signing algorithm. When null the existing default (RSA with SHA-256) is used.
    // Accepts the JWT-style names from AsymmetricKeyPairAlgorithm (RS256/RS384/RS512/ES256/ES384/ES512).
    private String signingAlgorithm;

    // Negative-test flags — deliberately produce a defective assertion so an SP's rejection paths can
    // be exercised. All default to false (a valid, correctly-signed assertion).
    private boolean expiredAssertion;
    private boolean wrongAudience;
    private boolean tamperedSignature;

    // Optional PEM-encoded signing credential. When null a self-signed RSA credential is generated.
    private String signingCertificatePem;
    // Security: the private key must NEVER be serialized back out (key leak via JSON / metadata /
    // response). WRITE_ONLY lets an inbound PUT body supply it while excluding it from serialization.
    @JsonProperty(value = "signingPrivateKeyPem", access = JsonProperty.Access.WRITE_ONLY)
    private String signingPrivateKeyPem;

    public SamlProviderConfiguration() {
    }

    @JsonProperty("idpEntityId")
    public String getIdpEntityId() {
        return idpEntityId;
    }

    public SamlProviderConfiguration setIdpEntityId(String idpEntityId) {
        this.idpEntityId = idpEntityId;
        return this;
    }

    @JsonProperty("ssoServiceUrl")
    public String getSsoServiceUrl() {
        return ssoServiceUrl;
    }

    public SamlProviderConfiguration setSsoServiceUrl(String ssoServiceUrl) {
        this.ssoServiceUrl = ssoServiceUrl;
        return this;
    }

    @JsonProperty("metadataUrl")
    public String getMetadataUrl() {
        return metadataUrl;
    }

    public SamlProviderConfiguration setMetadataUrl(String metadataUrl) {
        this.metadataUrl = metadataUrl;
        return this;
    }

    @JsonProperty("spEntityId")
    public String getSpEntityId() {
        return spEntityId;
    }

    public SamlProviderConfiguration setSpEntityId(String spEntityId) {
        this.spEntityId = spEntityId;
        return this;
    }

    @JsonProperty("assertionConsumerServiceUrl")
    public String getAssertionConsumerServiceUrl() {
        return assertionConsumerServiceUrl;
    }

    public SamlProviderConfiguration setAssertionConsumerServiceUrl(String assertionConsumerServiceUrl) {
        this.assertionConsumerServiceUrl = assertionConsumerServiceUrl;
        return this;
    }

    @JsonProperty("sloServiceUrl")
    public String getSloServiceUrl() {
        return sloServiceUrl;
    }

    public SamlProviderConfiguration setSloServiceUrl(String sloServiceUrl) {
        this.sloServiceUrl = sloServiceUrl;
        return this;
    }

    @JsonProperty("spSingleLogoutServiceUrl")
    public String getSpSingleLogoutServiceUrl() {
        return spSingleLogoutServiceUrl;
    }

    public SamlProviderConfiguration setSpSingleLogoutServiceUrl(String spSingleLogoutServiceUrl) {
        this.spSingleLogoutServiceUrl = spSingleLogoutServiceUrl;
        return this;
    }

    @JsonProperty("subjectNameId")
    public String getSubjectNameId() {
        return subjectNameId;
    }

    public SamlProviderConfiguration setSubjectNameId(String subjectNameId) {
        this.subjectNameId = subjectNameId;
        return this;
    }

    @JsonProperty("nameIdFormat")
    public String getNameIdFormat() {
        return nameIdFormat;
    }

    public SamlProviderConfiguration setNameIdFormat(String nameIdFormat) {
        this.nameIdFormat = nameIdFormat;
        return this;
    }

    @JsonProperty("attributes")
    public Map<String, String> getAttributes() {
        return attributes;
    }

    public SamlProviderConfiguration setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
        return this;
    }

    @JsonProperty("sessionDurationSeconds")
    public long getSessionDurationSeconds() {
        return sessionDurationSeconds;
    }

    public SamlProviderConfiguration setSessionDurationSeconds(long sessionDurationSeconds) {
        this.sessionDurationSeconds = sessionDurationSeconds;
        return this;
    }

    @JsonProperty("signingAlgorithm")
    public String getSigningAlgorithm() {
        return signingAlgorithm;
    }

    public SamlProviderConfiguration setSigningAlgorithm(String signingAlgorithm) {
        this.signingAlgorithm = signingAlgorithm;
        return this;
    }

    @JsonProperty("expiredAssertion")
    public boolean isExpiredAssertion() {
        return expiredAssertion;
    }

    public SamlProviderConfiguration setExpiredAssertion(boolean expiredAssertion) {
        this.expiredAssertion = expiredAssertion;
        return this;
    }

    @JsonProperty("wrongAudience")
    public boolean isWrongAudience() {
        return wrongAudience;
    }

    public SamlProviderConfiguration setWrongAudience(boolean wrongAudience) {
        this.wrongAudience = wrongAudience;
        return this;
    }

    @JsonProperty("tamperedSignature")
    public boolean isTamperedSignature() {
        return tamperedSignature;
    }

    public SamlProviderConfiguration setTamperedSignature(boolean tamperedSignature) {
        this.tamperedSignature = tamperedSignature;
        return this;
    }

    @JsonProperty("signingCertificatePem")
    public String getSigningCertificatePem() {
        return signingCertificatePem;
    }

    public SamlProviderConfiguration setSigningCertificatePem(String signingCertificatePem) {
        this.signingCertificatePem = signingCertificatePem;
        return this;
    }

    @JsonIgnore
    public String getSigningPrivateKeyPem() {
        return signingPrivateKeyPem;
    }

    public SamlProviderConfiguration setSigningPrivateKeyPem(String signingPrivateKeyPem) {
        this.signingPrivateKeyPem = signingPrivateKeyPem;
        return this;
    }
}
