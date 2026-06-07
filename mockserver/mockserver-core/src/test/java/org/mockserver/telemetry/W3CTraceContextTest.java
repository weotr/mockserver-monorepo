package org.mockserver.telemetry;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

public class W3CTraceContextTest {

    @Test
    public void parsesValidTraceparent() {
        W3CTraceContext ctx = W3CTraceContext.parse(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null);
        assertThat(ctx.isValid(), is(true));
        assertThat(ctx.getVersion(), is("00"));
        assertThat(ctx.getTraceId(), is("4bf92f3577b34da6a3ce929d0e0e4736"));
        assertThat(ctx.getParentId(), is("00f067aa0ba902b7"));
        assertThat(ctx.getFlags(), is("01"));
        assertThat(ctx.getTraceState(), is(nullValue()));
    }

    @Test
    public void parsesTracestateAlongside() {
        W3CTraceContext ctx = W3CTraceContext.parse(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "rojo=00f067aa0ba902b7");
        assertThat(ctx.isValid(), is(true));
        assertThat(ctx.getTraceState(), is("rojo=00f067aa0ba902b7"));
    }

    @Test
    public void rejectsNullTraceparent() {
        assertThat(W3CTraceContext.parse(null, null), is(nullValue()));
    }

    @Test
    public void rejectsEmptyTraceparent() {
        assertThat(W3CTraceContext.parse("", null), is(nullValue()));
    }

    @Test
    public void rejectsTooFewParts() {
        assertThat(W3CTraceContext.parse("only-two", null), is(nullValue()));
        assertThat(W3CTraceContext.parse("one-two-three", null), is(nullValue()));
    }

    @Test
    public void roundTripsToTraceparentString() {
        String original = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        W3CTraceContext ctx = W3CTraceContext.parse(original, null);
        assertThat(ctx.toTraceparent(), is(original));
    }

    @Test
    public void invalidTraceIdLengthFailsValidation() {
        W3CTraceContext ctx = new W3CTraceContext("00", "tooshort", "00f067aa0ba902b7", "01", null);
        assertThat(ctx.isValid(), is(false));
    }

    @Test
    public void invalidParentIdLengthFailsValidation() {
        W3CTraceContext ctx = new W3CTraceContext("00", "4bf92f3577b34da6a3ce929d0e0e4736", "short", "01", null);
        assertThat(ctx.isValid(), is(false));
    }

    @Test
    public void nullVersionFailsValidation() {
        W3CTraceContext ctx = new W3CTraceContext(null, "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01", null);
        assertThat(ctx.isValid(), is(false));
    }

    @Test
    public void nullFlagsFailsValidation() {
        W3CTraceContext ctx = new W3CTraceContext("00", "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", null, null);
        assertThat(ctx.isValid(), is(false));
    }

    @Test
    public void parsesUnsampledFlags() {
        W3CTraceContext ctx = W3CTraceContext.parse(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00", null);
        assertThat(ctx.isValid(), is(true));
        assertThat(ctx.getFlags(), is("00"));
    }

    @Test
    public void toleratesLeadingAndTrailingWhitespace() {
        W3CTraceContext ctx = W3CTraceContext.parse(
            "  00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01  ", null);
        assertThat(ctx.isValid(), is(true));
        assertThat(ctx.getTraceId(), is("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    public void traceparentWithExtraFieldsIsParsed() {
        // future versions may add extra dash-separated fields; parse must tolerate this
        W3CTraceContext ctx = W3CTraceContext.parse(
            "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra", null);
        assertThat(ctx.isValid(), is(true));
        assertThat(ctx.getVersion(), is("01"));
    }
}
