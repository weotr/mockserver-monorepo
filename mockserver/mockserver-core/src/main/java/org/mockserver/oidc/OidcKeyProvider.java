package org.mockserver.oidc;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.socket.tls.PEMToFile;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.UUID;

/**
 * Resolves the {@link AsymmetricKeyPair} that an OIDC provider signs tokens with and publishes via
 * its JWKS endpoint.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If {@code jwkJson} is supplied — parse the JWK (which carries both key material and, when
 *       present, its {@code kid} and algorithm).</li>
 *   <li>If {@code privateKeyPem} is supplied — parse the private key; derive the public key from
 *       {@code certificatePem} when present, otherwise from the RSA private key's CRT parameters.</li>
 *   <li>Otherwise — generate a fresh key pair for {@code signingAlgorithm}.</li>
 * </ol>
 *
 * <p>Whatever the source, the resulting key pair's algorithm matches the configured
 * {@code signingAlgorithm} so the same key both signs tokens and is published in the JWKS — keeping
 * the sign/publish invariant intact. A supplied {@code keyId} (or the JWK's own {@code kid}) gives a
 * stable {@code kid} across restarts so JWKS-caching clients keep working; otherwise a random
 * {@code kid} is generated.
 */
public class OidcKeyProvider {

    public static AsymmetricKeyPair resolveKeyPair(OidcProviderConfiguration config) {
        AsymmetricKeyPairAlgorithm algorithm = parseAlgorithm(config.getSigningAlgorithm());

        if (notBlank(config.getJwkJson())) {
            return fromJwk(config, algorithm);
        }
        if (notBlank(config.getPrivateKeyPem())) {
            return fromPem(config, algorithm);
        }

        String keyId = notBlank(config.getKeyId()) ? config.getKeyId() : UUID.randomUUID().toString();
        KeyPair keyPair = AsymmetricKeyGenerator.createKeyPair(algorithm);
        return new AsymmetricKeyPair(keyId, algorithm, keyPair);
    }

    private static AsymmetricKeyPair fromJwk(OidcProviderConfiguration config, AsymmetricKeyPairAlgorithm algorithm) {
        try {
            JWK jwk = JWK.parse(config.getJwkJson());
            if (!jwk.isPrivate()) {
                throw new IllegalArgumentException("supplied jwkJson must contain a private key to sign tokens");
            }
            KeyPair keyPair;
            if (jwk instanceof RSAKey) {
                RSAKey rsaKey = (RSAKey) jwk;
                keyPair = new KeyPair(rsaKey.toPublicKey(), rsaKey.toPrivateKey());
            } else if (jwk instanceof ECKey) {
                ECKey ecKey = (ECKey) jwk;
                keyPair = new KeyPair(ecKey.toPublicKey(), ecKey.toPrivateKey());
            } else {
                throw new IllegalArgumentException("unsupported jwkJson key type: " + jwk.getKeyType());
            }
            validateKeyMatchesAlgorithm(keyPair, algorithm, "jwkJson");
            String keyId = notBlank(config.getKeyId()) ? config.getKeyId()
                : (jwk.getKeyID() != null ? jwk.getKeyID() : UUID.randomUUID().toString());
            return new AsymmetricKeyPair(keyId, algorithm, keyPair);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse supplied jwkJson: " + e.getMessage(), e);
        }
    }

    private static AsymmetricKeyPair fromPem(OidcProviderConfiguration config, AsymmetricKeyPairAlgorithm algorithm) {
        try {
            PrivateKey privateKey = PEMToFile.privateKeyFromPEM(config.getPrivateKeyPem());
            PublicKey publicKey;
            if (notBlank(config.getCertificatePem())) {
                X509Certificate certificate = PEMToFile.x509FromPEM(config.getCertificatePem());
                publicKey = certificate.getPublicKey();
            } else if (privateKey instanceof RSAPrivateCrtKey) {
                RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privateKey;
                publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            } else {
                throw new IllegalArgumentException(
                    "certificatePem is required to derive the public key when privateKeyPem is an EC or non-CRT RSA key");
            }
            KeyPair keyPair = new KeyPair(publicKey, privateKey);
            validateKeyMatchesAlgorithm(keyPair, algorithm, "privateKeyPem");
            String keyId = notBlank(config.getKeyId()) ? config.getKeyId() : UUID.randomUUID().toString();
            return new AsymmetricKeyPair(keyId, algorithm, keyPair);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse supplied privateKeyPem/certificatePem: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a JWS algorithm name ({@code RS256}, {@code ES256}, ...) to the matching
     * {@link AsymmetricKeyPairAlgorithm}. Defaults to RS256 when blank.
     */
    static AsymmetricKeyPairAlgorithm parseAlgorithm(String signingAlgorithm) {
        if (!notBlank(signingAlgorithm)) {
            return AsymmetricKeyPairAlgorithm.RSA2048_SHA256;
        }
        for (AsymmetricKeyPairAlgorithm algorithm : AsymmetricKeyPairAlgorithm.values()) {
            if (algorithm.getJwtAlgorithm().equalsIgnoreCase(signingAlgorithm.trim())) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("unsupported signingAlgorithm \"" + signingAlgorithm
            + "\" - supported values are RS256, RS384, RS512, ES256, ES384, ES512");
    }

    /**
     * Fails fast when a supplied key's type contradicts the configured {@code signingAlgorithm}
     * family (an RSA key with an {@code ES*} algorithm, or an EC key with an {@code RS*} algorithm).
     * Throwing {@link IllegalArgumentException} here surfaces a clear 400 at OIDC-provider generate
     * time, instead of an opaque signing failure on the first {@code /token} request.
     */
    private static void validateKeyMatchesAlgorithm(KeyPair keyPair, AsymmetricKeyPairAlgorithm algorithm, String source) {
        String expectedFamily = algorithm.getAlgorithm(); // "RSA" or "EC"
        String suppliedFamily = keyFamily(keyPair.getPrivate().getAlgorithm());
        if (!expectedFamily.equals(suppliedFamily)) {
            throw new IllegalArgumentException("supplied " + source + " contains a " + suppliedFamily
                + " key but signingAlgorithm \"" + algorithm.getJwtAlgorithm() + "\" requires a "
                + expectedFamily + " key - choose a matching signingAlgorithm (RS* for RSA keys, ES* for EC keys)");
        }
    }

    /**
     * Normalises a JCA key algorithm name ({@code RSA}, {@code EC}, {@code ECDSA}) to the key family
     * ({@code RSA} or {@code EC}) used by {@link AsymmetricKeyPairAlgorithm#getAlgorithm()}.
     */
    private static String keyFamily(String keyAlgorithm) {
        if (keyAlgorithm == null) {
            return "";
        }
        String upper = keyAlgorithm.toUpperCase();
        if (upper.startsWith("RSA")) {
            return "RSA";
        }
        if (upper.startsWith("EC")) { // "EC", "ECDSA"
            return "EC";
        }
        return upper;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
