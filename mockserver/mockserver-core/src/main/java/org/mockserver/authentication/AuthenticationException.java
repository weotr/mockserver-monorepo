package org.mockserver.authentication;

public class AuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean clientSafeMessage;

    public AuthenticationException(String message) {
        this(message, true);
    }

    public AuthenticationException(String message, Throwable throwable) {
        this(message, throwable, true);
    }

    public AuthenticationException(String message, boolean clientSafeMessage) {
        super(message);
        this.clientSafeMessage = clientSafeMessage;
    }

    public AuthenticationException(String message, Throwable throwable, boolean clientSafeMessage) {
        super(message, throwable);
        this.clientSafeMessage = clientSafeMessage;
    }

    /**
     * Whether the exception message is safe to echo to the (unauthenticated) client in
     * the 401 response body. Legacy JWT / mTLS handlers return {@code true} (their
     * detailed message has always been surfaced to the client). The OIDC handler returns
     * {@code false} so the detail (expected issuer, audience, required scopes, signature
     * reasons) is logged SERVER-SIDE only and the client receives a generic body.
     */
    public boolean isClientSafeMessage() {
        return clientSafeMessage;
    }

}
