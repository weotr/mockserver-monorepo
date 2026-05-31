package org.mockserver.model;

import org.junit.Test;
import org.mockserver.serialization.model.GrpcChaosProfileDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.GrpcChaosProfile.grpcChaosProfile;

public class GrpcChaosProfileTest {

    @Test
    public void shouldBuildWithFluentApi() {
        GrpcChaosProfile profile = grpcChaosProfile()
            .withErrorStatusCode("UNAVAILABLE")
            .withErrorMessage("service down")
            .withErrorProbability(0.5)
            .withSeed(42L)
            .withLatencyMs(100L)
            .withSucceedFirst(3)
            .withFailRequestCount(5)
            .withQuotaName("my-quota")
            .withQuotaLimit(10)
            .withQuotaWindowMillis(60_000L);

        assertThat(profile.getErrorStatusCode(), is("UNAVAILABLE"));
        assertThat(profile.getErrorMessage(), is("service down"));
        assertThat(profile.getErrorProbability(), is(0.5));
        assertThat(profile.getSeed(), is(42L));
        assertThat(profile.getLatencyMs(), is(100L));
        assertThat(profile.getSucceedFirst(), is(3));
        assertThat(profile.getFailRequestCount(), is(5));
        assertThat(profile.getQuotaName(), is("my-quota"));
        assertThat(profile.getQuotaLimit(), is(10));
        assertThat(profile.getQuotaWindowMillis(), is(60_000L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidErrorStatusCode() {
        grpcChaosProfile().withErrorStatusCode("NOT_A_VALID_CODE");
    }

    @Test
    public void shouldAcceptAllValidGrpcStatusCodes() {
        // should not throw for any valid code
        grpcChaosProfile().withErrorStatusCode("OK");
        grpcChaosProfile().withErrorStatusCode("CANCELLED");
        grpcChaosProfile().withErrorStatusCode("UNKNOWN");
        grpcChaosProfile().withErrorStatusCode("INVALID_ARGUMENT");
        grpcChaosProfile().withErrorStatusCode("DEADLINE_EXCEEDED");
        grpcChaosProfile().withErrorStatusCode("NOT_FOUND");
        grpcChaosProfile().withErrorStatusCode("ALREADY_EXISTS");
        grpcChaosProfile().withErrorStatusCode("PERMISSION_DENIED");
        grpcChaosProfile().withErrorStatusCode("RESOURCE_EXHAUSTED");
        grpcChaosProfile().withErrorStatusCode("FAILED_PRECONDITION");
        grpcChaosProfile().withErrorStatusCode("ABORTED");
        grpcChaosProfile().withErrorStatusCode("OUT_OF_RANGE");
        grpcChaosProfile().withErrorStatusCode("UNIMPLEMENTED");
        grpcChaosProfile().withErrorStatusCode("INTERNAL");
        grpcChaosProfile().withErrorStatusCode("UNAVAILABLE");
        grpcChaosProfile().withErrorStatusCode("DATA_LOSS");
        grpcChaosProfile().withErrorStatusCode("UNAUTHENTICATED");
    }

    @Test
    public void shouldAcceptNullErrorStatusCode() {
        GrpcChaosProfile profile = grpcChaosProfile().withErrorStatusCode(null);
        assertThat(profile.getErrorStatusCode(), is(nullValue()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectErrorProbabilityBelowZero() {
        grpcChaosProfile().withErrorProbability(-0.1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectErrorProbabilityAboveOne() {
        grpcChaosProfile().withErrorProbability(1.1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNaNErrorProbability() {
        grpcChaosProfile().withErrorProbability(Double.NaN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeLatencyMs() {
        grpcChaosProfile().withLatencyMs(-1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeSucceedFirst() {
        grpcChaosProfile().withSucceedFirst(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroFailRequestCount() {
        grpcChaosProfile().withFailRequestCount(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroQuotaLimit() {
        grpcChaosProfile().withQuotaLimit(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroQuotaWindowMillis() {
        grpcChaosProfile().withQuotaWindowMillis(0L);
    }

    @Test
    public void hasAnyFaultReturnsFalseForEmptyProfile() {
        assertThat(grpcChaosProfile().hasAnyFault(), is(false));
    }

    @Test
    public void hasAnyFaultReturnsTrueForEachFault() {
        assertThat(grpcChaosProfile().withErrorProbability(0.5).hasAnyFault(), is(true));
        assertThat(grpcChaosProfile().withErrorStatusCode("INTERNAL").hasAnyFault(), is(true));
        assertThat(grpcChaosProfile().withLatencyMs(100L).hasAnyFault(), is(true));
        assertThat(grpcChaosProfile().withQuotaName("q").withQuotaLimit(5).withQuotaWindowMillis(1000L).hasAnyFault(), is(true));
    }

    @Test
    public void hasAnyFaultReturnsFalseForZeroProbabilityAndZeroLatency() {
        assertThat(grpcChaosProfile().withErrorProbability(0.0).hasAnyFault(), is(false));
        assertThat(grpcChaosProfile().withLatencyMs(0L).hasAnyFault(), is(false));
    }

    @Test
    public void countWindowEligibleWithBothNull() {
        GrpcChaosProfile profile = grpcChaosProfile();
        assertThat(profile.countWindowEligible(0), is(true));
        assertThat(profile.countWindowEligible(1), is(true));
        assertThat(profile.countWindowEligible(100), is(true));
    }

    @Test
    public void countWindowEligibleWithSucceedFirst() {
        GrpcChaosProfile profile = grpcChaosProfile().withSucceedFirst(3);
        assertThat("match 1 is not eligible", profile.countWindowEligible(1), is(false));
        assertThat("match 3 is not eligible", profile.countWindowEligible(3), is(false));
        assertThat("match 4 is eligible", profile.countWindowEligible(4), is(true));
        assertThat("match 100 is eligible (no failRequestCount)", profile.countWindowEligible(100), is(true));
    }

    @Test
    public void countWindowEligibleWithBothBounds() {
        GrpcChaosProfile profile = grpcChaosProfile().withSucceedFirst(2).withFailRequestCount(3);
        assertThat("match 1 not eligible (before window)", profile.countWindowEligible(1), is(false));
        assertThat("match 2 not eligible (still in succeedFirst)", profile.countWindowEligible(2), is(false));
        assertThat("match 3 eligible (window opens)", profile.countWindowEligible(3), is(true));
        assertThat("match 5 eligible (last in window)", profile.countWindowEligible(5), is(true));
        assertThat("match 6 not eligible (after window)", profile.countWindowEligible(6), is(false));
    }

    @Test
    public void dtoBuildObjectRoundTrip() {
        GrpcChaosProfile original = grpcChaosProfile()
            .withErrorStatusCode("DEADLINE_EXCEEDED")
            .withErrorMessage("timed out")
            .withErrorProbability(0.75)
            .withSeed(99L)
            .withLatencyMs(500L)
            .withSucceedFirst(2)
            .withFailRequestCount(10)
            .withQuotaName("rate-limit")
            .withQuotaLimit(5)
            .withQuotaWindowMillis(30_000L);

        GrpcChaosProfileDTO dto = new GrpcChaosProfileDTO(original);
        GrpcChaosProfile rebuilt = dto.buildObject();

        assertThat(rebuilt.getErrorStatusCode(), is("DEADLINE_EXCEEDED"));
        assertThat(rebuilt.getErrorMessage(), is("timed out"));
        assertThat(rebuilt.getErrorProbability(), is(0.75));
        assertThat(rebuilt.getSeed(), is(99L));
        assertThat(rebuilt.getLatencyMs(), is(500L));
        assertThat(rebuilt.getSucceedFirst(), is(2));
        assertThat(rebuilt.getFailRequestCount(), is(10));
        assertThat(rebuilt.getQuotaName(), is("rate-limit"));
        assertThat(rebuilt.getQuotaLimit(), is(5));
        assertThat(rebuilt.getQuotaWindowMillis(), is(30_000L));
    }

    @Test
    public void dtoBuildObjectWithNullProfile() {
        GrpcChaosProfileDTO dto = new GrpcChaosProfileDTO(null);
        GrpcChaosProfile rebuilt = dto.buildObject();
        assertThat(rebuilt.getErrorStatusCode(), is(nullValue()));
        assertThat(rebuilt.getErrorProbability(), is(nullValue()));
    }

    @Test
    public void equalsAndHashCode() {
        GrpcChaosProfile a = grpcChaosProfile().withErrorProbability(0.5).withErrorStatusCode("INTERNAL");
        GrpcChaosProfile b = grpcChaosProfile().withErrorProbability(0.5).withErrorStatusCode("INTERNAL");
        GrpcChaosProfile c = grpcChaosProfile().withErrorProbability(0.5).withErrorStatusCode("UNAVAILABLE");

        assertThat(a.equals(b), is(true));
        assertThat(a.hashCode(), is(b.hashCode()));
        assertThat(a.equals(c), is(false));
    }
}
