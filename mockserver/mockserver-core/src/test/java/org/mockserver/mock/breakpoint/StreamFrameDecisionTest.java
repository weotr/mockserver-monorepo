package org.mockserver.mock.breakpoint;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class StreamFrameDecisionTest {

    @Test
    public void shouldCreateContinueDecision() {
        StreamFrameDecision decision = StreamFrameDecision.continueFrame();
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CONTINUE));
        assertThat(decision.getReplacementBody(), is(nullValue()));
        assertThat(decision.getInjectedBody(), is(nullValue()));
    }

    @Test
    public void shouldCreateModifyDecision() {
        byte[] body = "modified".getBytes(StandardCharsets.UTF_8);
        StreamFrameDecision decision = StreamFrameDecision.modify(body);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.MODIFY));
        assertThat(decision.getReplacementBody(), is(body));
        assertThat(decision.getInjectedBody(), is(nullValue()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullReplacementBody() {
        StreamFrameDecision.modify(null);
    }

    @Test
    public void shouldCreateDropDecision() {
        StreamFrameDecision decision = StreamFrameDecision.drop();
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.DROP));
        assertThat(decision.getReplacementBody(), is(nullValue()));
        assertThat(decision.getInjectedBody(), is(nullValue()));
    }

    @Test
    public void shouldCreateInjectDecision() {
        byte[] body = "injected".getBytes(StandardCharsets.UTF_8);
        StreamFrameDecision decision = StreamFrameDecision.inject(body);
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.INJECT));
        assertThat(decision.getReplacementBody(), is(nullValue()));
        assertThat(decision.getInjectedBody(), is(body));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullInjectedBody() {
        StreamFrameDecision.inject(null);
    }

    @Test
    public void shouldCreateCloseDecision() {
        StreamFrameDecision decision = StreamFrameDecision.close();
        assertThat(decision.getAction(), is(StreamFrameDecision.Action.CLOSE));
        assertThat(decision.getReplacementBody(), is(nullValue()));
        assertThat(decision.getInjectedBody(), is(nullValue()));
    }
}
