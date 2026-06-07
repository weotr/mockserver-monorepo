package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotSame;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;

public class WebSocketMessageMatcherTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertEquals(webSocketMessageMatcher(), webSocketMessageMatcher());
        assertNotSame(webSocketMessageMatcher(), webSocketMessageMatcher());
    }

    @Test
    public void shouldHaveDefaultFrameTypeAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher();
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.ANY));
        assertThat(matcher.getTextMatcher(), is(nullValue()));
        assertThat(matcher.getResponses(), is(nullValue()));
    }

    @Test
    public void shouldSetTextAndFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello");

        assertThat(matcher.getTextMatcher().getValue(), is("hello"));
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.TEXT));
    }

    @Test
    public void shouldSetTextRegex() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("hello.*");

        assertThat(matcher.getTextMatcher().getValue(), is("hello.*"));
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.TEXT));
    }

    @Test
    public void shouldSetTextMatcher() {
        NottableString nottable = NottableString.string("test");
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextMatcher(nottable);

        assertThat(matcher.getTextMatcher(), is(nottable));
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.TEXT));
    }

    @Test
    public void shouldSetFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.BINARY);

        assertThat(matcher.getFrameType(), is(WebSocketFrameType.BINARY));
    }

    @Test
    public void shouldSetResponsesViaVarargs() {
        WebSocketMessage response1 = webSocketMessage("reply1");
        WebSocketMessage response2 = webSocketMessage("reply2");

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response1, response2);

        assertThat(matcher.getResponses(), hasSize(2));
        assertThat(matcher.getResponses(), containsInAnyOrder(response1, response2));
    }

    @Test
    public void shouldSetResponsesViaList() {
        WebSocketMessage response1 = webSocketMessage("reply1");
        WebSocketMessage response2 = webSocketMessage("reply2");
        List<WebSocketMessage> responses = Arrays.asList(response1, response2);

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(responses);

        assertThat(matcher.getResponses(), hasSize(2));
        assertThat(matcher.getResponses(), containsInAnyOrder(response1, response2));
    }

    @Test
    public void shouldAddSingleResponse() {
        WebSocketMessage response1 = webSocketMessage("reply1");
        WebSocketMessage response2 = webSocketMessage("reply2");

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponse(response1)
            .withResponse(response2);

        assertThat(matcher.getResponses(), hasSize(2));
        assertThat(matcher.getResponses(), containsInAnyOrder(response1, response2));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        WebSocketMessage response = webSocketMessage("reply");
        WebSocketMessageMatcher matcherOne = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);
        WebSocketMessageMatcher matcherTwo = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);

        assertThat(matcherOne, is(matcherTwo));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        WebSocketMessage response = webSocketMessage("reply");
        WebSocketMessageMatcher matcherOne = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);
        WebSocketMessageMatcher matcherTwo = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);

        assertThat(matcherOne.hashCode(), is(matcherTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentText() {
        assertThat(
            webSocketMessageMatcher().withText("hello"),
            is(not(webSocketMessageMatcher().withText("world")))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentFrameType() {
        assertThat(
            webSocketMessageMatcher().withFrameType(WebSocketFrameType.TEXT),
            is(not(webSocketMessageMatcher().withFrameType(WebSocketFrameType.BINARY)))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentResponses() {
        assertThat(
            webSocketMessageMatcher().withResponses(webSocketMessage("a")),
            is(not(webSocketMessageMatcher().withResponses(webSocketMessage("b"))))
        );
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(webSocketMessageMatcher().withText("hello").equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(webSocketMessageMatcher().withText("hello").equals("hello"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher().withText("hello");
        assertThat(matcher, is(matcher));
    }
}
