package org.mockserver.authentication;

import com.nimbusds.jose.util.DefaultResourceRetriever;

/**
 * Bounded {@link com.nimbusds.jose.util.ResourceRetriever} for control-plane JWK-set and OIDC
 * discovery-document fetches.
 * <p>
 * Nimbus's default {@code RemoteJWKSet} retriever uses an INFINITE connect/read timeout and NO
 * response size limit. These fetches happen on (or feed) the control-plane auth path, so an
 * unreachable or hostile JWKS/discovery endpoint could hang the fetch indefinitely or stream an
 * unbounded body — a DoS / SSRF-amplification vector. This retriever applies finite connect/read
 * timeouts and a sane response size cap so a slow or oversized endpoint fails fast instead.
 */
public final class BoundedResourceRetriever {

    /** Connect timeout in milliseconds for control-plane JWKS / OIDC discovery fetches. */
    public static final int CONNECT_TIMEOUT_MILLIS = 3000;
    /** Read timeout in milliseconds for control-plane JWKS / OIDC discovery fetches. */
    public static final int READ_TIMEOUT_MILLIS = 3000;
    /** Maximum response body size in bytes; a real JWK set / discovery document is a few KB. */
    public static final int SIZE_LIMIT_BYTES = 50 * 1024;

    private BoundedResourceRetriever() {
    }

    public static DefaultResourceRetriever create() {
        return new DefaultResourceRetriever(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, SIZE_LIMIT_BYTES);
    }
}
