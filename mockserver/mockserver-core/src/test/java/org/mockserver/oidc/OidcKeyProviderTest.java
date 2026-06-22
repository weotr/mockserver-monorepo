package org.mockserver.oidc;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.Test;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;

import java.security.interfaces.RSAPrivateCrtKey;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link OidcKeyProvider} key-material resolution. These do not touch the
 * {@link OidcAuthorizationStore} singleton, so they are safe to run in parallel.
 */
public class OidcKeyProviderTest {

    @Test
    public void defaultsGenerateRsa256KeyPair() {
        AsymmetricKeyPair keyPair = OidcKeyProvider.resolveKeyPair(new OidcProviderConfiguration());
        assertThat(keyPair.getAlgorithm(), is(AsymmetricKeyPairAlgorithm.RSA2048_SHA256));
        assertThat(keyPair.getKeyId(), is(notNullValue()));
    }

    @Test
    public void parsesEachSupportedAlgorithm() {
        assertThat(OidcKeyProvider.parseAlgorithm("RS256"), is(AsymmetricKeyPairAlgorithm.RSA2048_SHA256));
        assertThat(OidcKeyProvider.parseAlgorithm("RS384"), is(AsymmetricKeyPairAlgorithm.RSA3072_SHA384));
        assertThat(OidcKeyProvider.parseAlgorithm("RS512"), is(AsymmetricKeyPairAlgorithm.RSA4096_SHA512));
        assertThat(OidcKeyProvider.parseAlgorithm("ES256"), is(AsymmetricKeyPairAlgorithm.EC256_SHA256));
        assertThat(OidcKeyProvider.parseAlgorithm("ES384"), is(AsymmetricKeyPairAlgorithm.EC384_SHA384));
        assertThat(OidcKeyProvider.parseAlgorithm("ES512"), is(AsymmetricKeyPairAlgorithm.ECP512_SHA512));
    }

    @Test
    public void blankAlgorithmDefaultsToRs256() {
        assertThat(OidcKeyProvider.parseAlgorithm(null), is(AsymmetricKeyPairAlgorithm.RSA2048_SHA256));
        assertThat(OidcKeyProvider.parseAlgorithm("  "), is(AsymmetricKeyPairAlgorithm.RSA2048_SHA256));
    }

    @Test
    public void unknownAlgorithmThrows() {
        assertThrows(IllegalArgumentException.class, () -> OidcKeyProvider.parseAlgorithm("HS256"));
    }

    @Test
    public void suppliedKeyIdGivesStableKid() {
        OidcProviderConfiguration config = new OidcProviderConfiguration().setKeyId("fixed-kid");
        assertThat(OidcKeyProvider.resolveKeyPair(config).getKeyId(), is("fixed-kid"));
        assertThat(OidcKeyProvider.resolveKeyPair(config).getKeyId(), is("fixed-kid"));
    }

    @Test
    public void suppliedJwkJsonIsUsed() throws Exception {
        String jwkJson = new RSAKeyGenerator(2048).keyID("from-jwk").generate().toJSONString();
        OidcProviderConfiguration config = new OidcProviderConfiguration().setJwkJson(jwkJson);

        AsymmetricKeyPair keyPair = OidcKeyProvider.resolveKeyPair(config);
        assertThat(keyPair.getKeyId(), is("from-jwk"));
        assertThat(keyPair.getAlgorithm(), is(AsymmetricKeyPairAlgorithm.RSA2048_SHA256));
        assertTrue(keyPair.getKeyPair().getPrivate() instanceof RSAPrivateCrtKey);
    }

    @Test
    public void publicJwkWithoutPrivateKeyIsRejected() throws Exception {
        String publicOnly = new RSAKeyGenerator(2048).generate().toPublicJWK().toJSONString();
        OidcProviderConfiguration config = new OidcProviderConfiguration().setJwkJson(publicOnly);
        assertThrows(IllegalArgumentException.class, () -> OidcKeyProvider.resolveKeyPair(config));
    }

    @Test
    public void suppliedEcJwkIsUsedWhenAlgorithmMatches() throws Exception {
        String ecJwk = new ECKeyGenerator(Curve.P_256).keyID("ec-kid").generate().toJSONString();
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setSigningAlgorithm("ES256")
            .setJwkJson(ecJwk);

        AsymmetricKeyPair keyPair = OidcKeyProvider.resolveKeyPair(config);
        assertThat(keyPair.getAlgorithm(), is(AsymmetricKeyPairAlgorithm.EC256_SHA256));
        assertThat(keyPair.getKeyId(), is("ec-kid"));
    }

    @Test
    public void ecJwkWithRsaSigningAlgorithmIsRejectedAtGenerateTime() throws Exception {
        String ecJwk = new ECKeyGenerator(Curve.P_256).generate().toJSONString();
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setSigningAlgorithm("RS256")
            .setJwkJson(ecJwk);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> OidcKeyProvider.resolveKeyPair(config));
        assertThat(e.getMessage(), containsString("EC"));
        assertThat(e.getMessage(), containsString("RS256"));
    }

    @Test
    public void rsaJwkWithEcSigningAlgorithmIsRejectedAtGenerateTime() throws Exception {
        String rsaJwk = new RSAKeyGenerator(2048).generate().toJSONString();
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setSigningAlgorithm("ES256")
            .setJwkJson(rsaJwk);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> OidcKeyProvider.resolveKeyPair(config));
        assertThat(e.getMessage(), containsString("RSA"));
        assertThat(e.getMessage(), containsString("ES256"));
    }
}
