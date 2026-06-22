package org.mockserver.saml;

import org.mockserver.keys.AsymmetricKeyPairAlgorithm;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory state backing the mock SAML SSO flow.
 *
 * <p>Holds one {@link Provider} per generated mock IdP, keyed by the {@code ssoServiceUrl} it
 * serves. The {@code /saml/sso} class callback ({@link SamlSsoCallback}) looks up its provider by
 * the request path to build, sign, and POST a SAML {@code Response} back to the SP's assertion
 * consumer service.
 *
 * <p>This is a process-wide singleton (mirroring {@link org.mockserver.oidc.OidcAuthorizationStore}
 * and the other in-memory registries) because the SSO class callback is instantiated fresh per
 * request and therefore cannot share instance state.
 */
public class SamlAssertionStore {

    private static final SamlAssertionStore INSTANCE = new SamlAssertionStore();

    private final List<Provider> providers = new CopyOnWriteArrayList<>();

    SamlAssertionStore() {
    }

    public static SamlAssertionStore getInstance() {
        return INSTANCE;
    }

    /**
     * Registers (or replaces) the provider serving the given SSO path. The most recently registered
     * provider for a path wins, so re-running {@code PUT /mockserver/saml} with the same SSO path
     * refreshes the signing credential and attributes.
     */
    public synchronized void registerProvider(Provider provider) {
        providers.removeIf(existing -> existing.ssoServiceUrl.equals(provider.ssoServiceUrl));
        providers.add(0, provider);
    }

    /**
     * Finds the provider serving the given SSO path, or {@code null} if none registered.
     */
    public Provider providerForSsoPath(String ssoServiceUrl) {
        for (Provider provider : providers) {
            if (provider.ssoServiceUrl.equals(ssoServiceUrl)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider serving the given Single-Logout (SLO) path, or {@code null} if none
     * registered (or the provider has no SLO endpoint configured).
     */
    public Provider providerForSloPath(String sloServiceUrl) {
        if (sloServiceUrl == null) {
            return null;
        }
        for (Provider provider : providers) {
            if (sloServiceUrl.equals(provider.sloServiceUrl)) {
                return provider;
            }
        }
        return null;
    }

    public void reset() {
        providers.clear();
    }

    /**
     * Immutable description of a generated mock SAML IdP, holding everything the SSO callback needs
     * to mint and sign a fresh {@code Response} per request.
     */
    public static class Provider {
        final String ssoServiceUrl;
        final String sloServiceUrl;
        final String spSingleLogoutServiceUrl;
        final String idpEntityId;
        final String spEntityId;
        final String assertionConsumerServiceUrl;
        final String subjectNameId;
        final String nameIdFormat;
        final Map<String, String> attributes;
        final long sessionDurationSeconds;
        final PrivateKey signingPrivateKey;
        final X509Certificate signingCertificate;
        final AsymmetricKeyPairAlgorithm signingAlgorithm;
        // negative-test flags — when set the SSO callback mints a deliberately-defective assertion
        final boolean expiredAssertion;
        final boolean wrongAudience;
        final boolean tamperedSignature;

        public Provider(String ssoServiceUrl, String sloServiceUrl, String spSingleLogoutServiceUrl,
                        String idpEntityId, String spEntityId,
                        String assertionConsumerServiceUrl, String subjectNameId, String nameIdFormat,
                        Map<String, String> attributes, long sessionDurationSeconds,
                        PrivateKey signingPrivateKey, X509Certificate signingCertificate,
                        AsymmetricKeyPairAlgorithm signingAlgorithm,
                        boolean expiredAssertion, boolean wrongAudience, boolean tamperedSignature) {
            this.ssoServiceUrl = ssoServiceUrl;
            this.sloServiceUrl = sloServiceUrl;
            this.spSingleLogoutServiceUrl = spSingleLogoutServiceUrl;
            this.idpEntityId = idpEntityId;
            this.spEntityId = spEntityId;
            this.assertionConsumerServiceUrl = assertionConsumerServiceUrl;
            this.subjectNameId = subjectNameId;
            this.nameIdFormat = nameIdFormat;
            this.attributes = attributes;
            this.sessionDurationSeconds = sessionDurationSeconds;
            this.signingPrivateKey = signingPrivateKey;
            this.signingCertificate = signingCertificate;
            this.signingAlgorithm = signingAlgorithm;
            this.expiredAssertion = expiredAssertion;
            this.wrongAudience = wrongAudience;
            this.tamperedSignature = tamperedSignature;
        }
    }
}
