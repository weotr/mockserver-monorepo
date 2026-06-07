package org.mockserver.async.asyncapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed AsyncAPI specification containing the channels and their message definitions.
 */
public class AsyncApiSpec {

    private final String asyncApiVersion;
    private final String title;
    private final List<AsyncApiChannel> channels;

    public AsyncApiSpec(String asyncApiVersion, String title, List<AsyncApiChannel> channels) {
        this.asyncApiVersion = asyncApiVersion;
        this.title = title;
        this.channels = channels != null
            ? Collections.unmodifiableList(new ArrayList<>(channels))
            : Collections.emptyList();
    }

    /**
     * The AsyncAPI version string (e.g. "2.6.0" or "3.0.0").
     */
    public String getAsyncApiVersion() {
        return asyncApiVersion;
    }

    /**
     * The title from info.title, or null if absent.
     */
    public String getTitle() {
        return title;
    }

    /**
     * All channels extracted from the spec.
     */
    public List<AsyncApiChannel> getChannels() {
        return channels;
    }
}
