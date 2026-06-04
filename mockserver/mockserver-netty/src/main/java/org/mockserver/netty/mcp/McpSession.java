package org.mockserver.netty.mcp;

import java.util.function.LongSupplier;

public class McpSession {

    private final String sessionId;
    private final long createdAt;
    private volatile boolean initialized;
    private volatile long lastAccessedAt;
    private final LongSupplier clock;

    public McpSession(String sessionId) {
        this(sessionId, System::currentTimeMillis);
    }

    McpSession(String sessionId, LongSupplier clock) {
        this.sessionId = sessionId;
        this.clock = clock;
        this.createdAt = clock.getAsLong();
        this.lastAccessedAt = this.createdAt;
        this.initialized = false;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void markInitialized() {
        this.initialized = true;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void touch() {
        this.lastAccessedAt = clock.getAsLong();
    }
}
