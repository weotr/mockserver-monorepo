package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotSame;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.StreamingPhysics.streamingPhysics;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.model.Usage.usage;

public class CompletionTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertEquals(completion(), completion());
        assertNotSame(completion(), completion());
    }

    @Test
    public void shouldBuildWithAllFields() {
        // given
        ToolUse tool = toolUse("get_weather").withArguments("{\"city\":\"London\"}");
        Usage usageObj = usage().withInputTokens(10).withOutputTokens(20);
        StreamingPhysics physics = streamingPhysics().withTokensPerSecond(100);

        // when
        Completion c = completion()
            .withText("Hello")
            .withToolCalls(tool)
            .withStopReason("end_turn")
            .withUsage(usageObj)
            .withStreaming(true)
            .withStreamingPhysics(physics);

        // then
        assertThat(c.getText(), is("Hello"));
        assertThat(c.getToolCalls(), hasSize(1));
        assertThat(c.getToolCalls().get(0), is(tool));
        assertThat(c.getStopReason(), is("end_turn"));
        assertThat(c.getUsage(), is(usageObj));
        assertThat(c.getStreaming(), is(true));
        assertThat(c.getStreamingPhysics(), is(physics));
    }

    @Test
    public void shouldHaveNullDefaultValues() {
        // when
        Completion c = completion();

        // then
        assertThat(c.getText(), is(nullValue()));
        assertThat(c.getToolCalls(), is(nullValue()));
        assertThat(c.getStopReason(), is(nullValue()));
        assertThat(c.getUsage(), is(nullValue()));
        assertThat(c.getStreaming(), is(nullValue()));
        assertThat(c.getStreamingPhysics(), is(nullValue()));
    }

    @Test
    public void shouldAddSingleToolCall() {
        // given
        ToolUse tool1 = toolUse("tool1");
        ToolUse tool2 = toolUse("tool2");

        // when
        Completion c = completion()
            .withToolCall(tool1)
            .withToolCall(tool2);

        // then
        assertThat(c.getToolCalls(), hasSize(2));
    }

    @Test
    public void shouldAddMultipleToolCallsViaList() {
        // given
        ToolUse tool1 = toolUse("tool1");
        ToolUse tool2 = toolUse("tool2");

        // when
        Completion c = completion()
            .withToolCalls(Arrays.asList(tool1, tool2));

        // then
        assertThat(c.getToolCalls(), hasSize(2));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        // given
        Completion c1 = completion().withText("Hello").withStopReason("end_turn");
        Completion c2 = completion().withText("Hello").withStopReason("end_turn");

        // then
        assertThat(c1, is(c2));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        // given
        Completion c1 = completion().withText("Hello").withStopReason("end_turn");
        Completion c2 = completion().withText("Hello").withStopReason("end_turn");

        // then
        assertThat(c1.hashCode(), is(c2.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentText() {
        assertThat(completion().withText("Hello"), is(not(completion().withText("World"))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentStopReason() {
        assertThat(completion().withStopReason("end_turn"), is(not(completion().withStopReason("max_tokens"))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentOutputSchema() {
        assertThat(completion().withOutputSchema("{\"type\":\"object\"}"),
            is(not(completion().withOutputSchema("{\"type\":\"array\"}"))));
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(completion().withText("Hello").equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(completion().withText("Hello").equals("string"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        // given
        Completion c = completion().withText("Hello");

        // then
        assertThat(c, is(c));
    }

    // ToolUse tests

    @Test
    public void shouldBuildToolUse() {
        // when
        ToolUse tool = toolUse("get_weather").withArguments("{\"city\":\"London\"}");

        // then
        assertThat(tool.getName(), is("get_weather"));
        assertThat(tool.getArguments(), is("{\"city\":\"London\"}"));
    }

    @Test
    public void shouldHaveToolUseEquality() {
        // given
        ToolUse t1 = toolUse("fn").withArguments("{}");
        ToolUse t2 = toolUse("fn").withArguments("{}");

        // then
        assertThat(t1, is(t2));
        assertThat(t1.hashCode(), is(t2.hashCode()));
    }

    @Test
    public void shouldHaveToolUseInequality() {
        assertThat(toolUse("fn1"), is(not(toolUse("fn2"))));
    }

    // Usage tests

    @Test
    public void shouldBuildUsage() {
        // when
        Usage u = usage().withInputTokens(10).withOutputTokens(20);

        // then
        assertThat(u.getInputTokens(), is(10));
        assertThat(u.getOutputTokens(), is(20));
    }

    @Test
    public void shouldBuildUsageFromFactories() {
        assertThat(Usage.inputTokens(10).getInputTokens(), is(10));
        assertThat(Usage.outputTokens(20).getOutputTokens(), is(20));
    }

    @Test
    public void shouldHaveUsageEquality() {
        // given
        Usage u1 = usage().withInputTokens(10).withOutputTokens(20);
        Usage u2 = usage().withInputTokens(10).withOutputTokens(20);

        // then
        assertThat(u1, is(u2));
        assertThat(u1.hashCode(), is(u2.hashCode()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeInputTokens() {
        usage().withInputTokens(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeOutputTokens() {
        usage().withOutputTokens(-1);
    }

    @Test
    public void shouldAcceptZeroTokens() {
        Usage u = usage().withInputTokens(0).withOutputTokens(0);
        assertThat(u.getInputTokens(), is(0));
        assertThat(u.getOutputTokens(), is(0));
    }

    // StreamingPhysics tests

    @Test
    public void shouldBuildStreamingPhysics() {
        // when
        StreamingPhysics sp = streamingPhysics()
            .withTimeToFirstToken(Delay.milliseconds(100))
            .withTokensPerSecond(50)
            .withJitter(0.5)
            .withSeed(42L);

        // then
        assertThat(sp.getTimeToFirstToken(), is(Delay.milliseconds(100)));
        assertThat(sp.getTokensPerSecond(), is(50));
        assertThat(sp.getJitter(), is(0.5));
        assertThat(sp.getSeed(), is(42L));
    }

    @Test
    public void shouldHaveStreamingPhysicsEquality() {
        // given
        StreamingPhysics sp1 = streamingPhysics().withTokensPerSecond(100).withJitter(0.1);
        StreamingPhysics sp2 = streamingPhysics().withTokensPerSecond(100).withJitter(0.1);

        // then
        assertThat(sp1, is(sp2));
        assertThat(sp1.hashCode(), is(sp2.hashCode()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTokensPerSecondBelowMin() {
        streamingPhysics().withTokensPerSecond(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTokensPerSecondAboveMax() {
        streamingPhysics().withTokensPerSecond(10001);
    }

    @Test
    public void shouldAcceptTokensPerSecondAtBoundaries() {
        assertThat(streamingPhysics().withTokensPerSecond(1).getTokensPerSecond(), is(1));
        assertThat(streamingPhysics().withTokensPerSecond(10000).getTokensPerSecond(), is(10000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectJitterBelowMin() {
        streamingPhysics().withJitter(-0.1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectJitterAboveMax() {
        streamingPhysics().withJitter(1.1);
    }

    @Test
    public void shouldAcceptJitterAtBoundaries() {
        assertThat(streamingPhysics().withJitter(0.0).getJitter(), is(0.0));
        assertThat(streamingPhysics().withJitter(1.0).getJitter(), is(1.0));
    }

    // EmbeddingResponse tests

    @Test
    public void shouldBuildEmbeddingResponse() {
        // when
        EmbeddingResponse e = EmbeddingResponse.embedding()
            .withDimensions(1536)
            .withDeterministicFromInput(true)
            .withSeed(42L);

        // then
        assertThat(e.getDimensions(), is(1536));
        assertThat(e.getDeterministicFromInput(), is(true));
        assertThat(e.getSeed(), is(42L));
    }

    @Test
    public void shouldHaveEmbeddingEquality() {
        // given
        EmbeddingResponse e1 = EmbeddingResponse.embedding().withDimensions(1536);
        EmbeddingResponse e2 = EmbeddingResponse.embedding().withDimensions(1536);

        // then
        assertThat(e1, is(e2));
        assertThat(e1.hashCode(), is(e2.hashCode()));
    }

    @Test
    public void shouldHaveEmbeddingInequality() {
        assertThat(
            EmbeddingResponse.embedding().withDimensions(1536),
            is(not(EmbeddingResponse.embedding().withDimensions(768)))
        );
    }
}
