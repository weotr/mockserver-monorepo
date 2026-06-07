package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class WebSocketMessageMatcher extends ObjectWithJsonToString {
    private int hashCode;
    private WebSocketFrameType frameType;
    private NottableString textMatcher;
    private List<WebSocketMessage> responses;

    public static WebSocketMessageMatcher webSocketMessageMatcher() {
        return new WebSocketMessageMatcher().withFrameType(WebSocketFrameType.ANY);
    }

    public WebSocketFrameType getFrameType() {
        return frameType;
    }

    public WebSocketMessageMatcher withFrameType(WebSocketFrameType frameType) {
        this.frameType = frameType;
        this.hashCode = 0;
        return this;
    }

    public NottableString getTextMatcher() {
        return textMatcher;
    }

    public WebSocketMessageMatcher withText(String text) {
        this.textMatcher = NottableString.string(text);
        this.frameType = WebSocketFrameType.TEXT;
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withTextMatcher(NottableString textMatcher) {
        this.textMatcher = textMatcher;
        if (textMatcher != null) {
            this.frameType = WebSocketFrameType.TEXT;
        }
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withTextRegex(String regex) {
        this.textMatcher = NottableString.string(regex);
        this.frameType = WebSocketFrameType.TEXT;
        this.hashCode = 0;
        return this;
    }

    public List<WebSocketMessage> getResponses() {
        return responses;
    }

    public WebSocketMessageMatcher withResponses(WebSocketMessage... responses) {
        this.responses = new ArrayList<>(Arrays.asList(responses));
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withResponses(List<WebSocketMessage> responses) {
        this.responses = responses;
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withResponse(WebSocketMessage response) {
        if (this.responses == null) {
            this.responses = new ArrayList<>();
        }
        this.responses.add(response);
        this.hashCode = 0;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        WebSocketMessageMatcher that = (WebSocketMessageMatcher) o;
        return frameType == that.frameType &&
            Objects.equals(textMatcher, that.textMatcher) &&
            Objects.equals(responses, that.responses);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(frameType, textMatcher, responses);
        }
        return hashCode;
    }
}
