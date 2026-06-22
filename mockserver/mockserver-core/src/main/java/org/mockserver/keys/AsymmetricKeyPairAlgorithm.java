package org.mockserver.keys;

public enum AsymmetricKeyPairAlgorithm {

    EC256_SHA256("EC", "ES256", "SHA256WITHECDSA", "secp256r1"),
    EC384_SHA384("EC", "ES384", "SHA384WITHECDSA", "secp384r1"),
    ECP512_SHA512("EC", "ES512", "SHA512WITHECDSA", "secp521r1"),
    RSA2048_SHA256("RSA", "RS256", "SHA256WithRSA", 2048),
    RSA3072_SHA384("RSA", "RS384", "SHA384WITHRSA", 3072),
    RSA4096_SHA512("RSA", "RS512", "SHA512WITHRSA", 4096);

    private final String algorithm;
    private String ecDomainParameters;
    private int keyLength;
    private final String jwtAlgorithm;
    private final String signingAlgorithm;

    AsymmetricKeyPairAlgorithm(String algorithm, String jwtAlgorithm, String signingAlgorithm, String ecDomainParameters) {
        this(algorithm, jwtAlgorithm, signingAlgorithm);
        this.ecDomainParameters = ecDomainParameters;
    }

    AsymmetricKeyPairAlgorithm(String algorithm, String jwtAlgorithm, String signingAlgorithm, int keyLength) {
        this(algorithm, jwtAlgorithm, signingAlgorithm);
        this.keyLength = keyLength;
    }

    AsymmetricKeyPairAlgorithm(String algorithm, String jwtAlgorithm, String signingAlgorithm) {
        this.algorithm = algorithm;
        this.jwtAlgorithm = jwtAlgorithm;
        this.signingAlgorithm = signingAlgorithm;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getJwtAlgorithm() {
        return jwtAlgorithm;
    }

    public String getSigningAlgorithm() {
        return signingAlgorithm;
    }

    public String getECDomainParameters() {
        return ecDomainParameters;
    }

    public int getKeyLength() {
        return keyLength;
    }

    /**
     * The W3C XML Signature {@code SignatureMethod} algorithm URI corresponding to this key/hash
     * pair, used when enveloped-signing SAML assertions with the JDK XML Digital Signature API.
     */
    public String getXmlSignatureMethod() {
        switch (this) {
            case EC256_SHA256:
                return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256";
            case EC384_SHA384:
                return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384";
            case ECP512_SHA512:
                return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512";
            case RSA3072_SHA384:
                return "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384";
            case RSA4096_SHA512:
                return "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512";
            case RSA2048_SHA256:
            default:
                return "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
        }
    }

    /**
     * The W3C XML Signature {@code DigestMethod} algorithm URI corresponding to this algorithm's hash.
     */
    public String getXmlDigestMethod() {
        switch (this) {
            case EC384_SHA384:
            case RSA3072_SHA384:
                return "http://www.w3.org/2001/04/xmldsig-more#sha384";
            case ECP512_SHA512:
            case RSA4096_SHA512:
                return "http://www.w3.org/2001/04/xmlenc#sha512";
            case EC256_SHA256:
            case RSA2048_SHA256:
            default:
                return "http://www.w3.org/2001/04/xmlenc#sha256";
        }
    }

    /**
     * Resolves an algorithm from its JWT-style short name (e.g. {@code RS256}, {@code ES384}),
     * case-insensitively, returning {@code null} when the name is null/blank/unrecognised so callers
     * can fall back to a default.
     */
    public static AsymmetricKeyPairAlgorithm fromJwtAlgorithm(String jwtAlgorithm) {
        if (jwtAlgorithm == null || jwtAlgorithm.trim().isEmpty()) {
            return null;
        }
        String normalised = jwtAlgorithm.trim();
        for (AsymmetricKeyPairAlgorithm algorithm : values()) {
            if (algorithm.jwtAlgorithm.equalsIgnoreCase(normalised)) {
                return algorithm;
            }
        }
        return null;
    }
}
