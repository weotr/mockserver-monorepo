package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.model.HttpChaosProfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class ServiceChaosRegistryPatchTest {

    @Test
    public void shouldPatchOnlyNonNullFields() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile base = httpChaosProfile()
            .withErrorProbability(0.5)
            .withDropConnectionProbability(0.1)
            .withErrorStatus(503);
        registry.put("example.com", base);

        HttpChaosProfile partial = httpChaosProfile()
            .withErrorProbability(0.8); // only patch this field
        registry.patch("example.com", partial);

        HttpChaosProfile result = registry.get("example.com");
        assertThat("patched field updated", result.getErrorProbability(), is(0.8));
        assertThat("unpatched field preserved", result.getDropConnectionProbability(), is(0.1));
        assertThat("unpatched field preserved", result.getErrorStatus(), is(503));
    }

    @Test
    public void shouldRegisterAsNewProfileWhenHostNotFound() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile partial = httpChaosProfile()
            .withErrorProbability(0.3);
        registry.patch("new.host", partial);

        HttpChaosProfile result = registry.get("new.host");
        assertThat("partial registered as new profile", result, is(notNullValue()));
        assertThat(result.getErrorProbability(), is(0.3));
    }

    @Test
    public void shouldPreserveTtlFromOriginalRegistration() {
        long[] clock = {1000L};
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> clock[0]);
        HttpChaosProfile base = httpChaosProfile().withErrorProbability(0.5);
        registry.put("host.example", base, 5000L); // expires at 6000

        clock[0] = 2000L; // advance 1s
        HttpChaosProfile partial = httpChaosProfile().withDropConnectionProbability(0.2);
        registry.patch("host.example", partial);

        clock[0] = 5500L; // still within TTL (expires at 6000)
        assertThat("within TTL after patch", registry.get("host.example"), is(notNullValue()));
        assertThat("patched field present", registry.get("host.example").getDropConnectionProbability(), is(0.2));
        assertThat("original field preserved", registry.get("host.example").getErrorProbability(), is(0.5));

        clock[0] = 6001L; // past TTL
        assertThat("expired after original TTL", registry.get("host.example"), is(nullValue()));
    }

    @Test
    public void shouldBeNoOpWhenHostIsNull() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile base = httpChaosProfile().withErrorProbability(0.5);
        registry.put("host", base);

        HttpChaosProfile result = registry.patch(null, httpChaosProfile().withErrorProbability(0.9));
        assertThat("null host returns null", result, is(nullValue()));
        assertThat("existing profile unchanged", registry.get("host").getErrorProbability(), is(0.5));
    }

    @Test
    public void shouldBeNoOpWhenPartialIsNull() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile base = httpChaosProfile().withErrorProbability(0.5);
        registry.put("host", base);

        HttpChaosProfile result = registry.patch("host", null);
        assertThat("null partial returns null", result, is(nullValue()));
        assertThat("existing profile unchanged", registry.get("host").getErrorProbability(), is(0.5));
    }

    @Test
    public void shouldReturnUpdatedProfile() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile base = httpChaosProfile()
            .withErrorProbability(0.5)
            .withErrorStatus(500);
        registry.put("host", base);

        HttpChaosProfile partial = httpChaosProfile().withErrorProbability(0.9);
        HttpChaosProfile result = registry.patch("host", partial);

        assertThat("returns merged profile", result, is(notNullValue()));
        assertThat("patched field in return value", result.getErrorProbability(), is(0.9));
        assertThat("preserved field in return value", result.getErrorStatus(), is(500));
    }

    @Test
    public void shouldPatchExpiredEntryAsNewRegistration() {
        long[] clock = {1000L};
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> clock[0]);
        HttpChaosProfile base = httpChaosProfile().withErrorProbability(0.5).withErrorStatus(503);
        registry.put("host", base, 2000L); // expires at 3000

        clock[0] = 4000L; // expired
        assertThat("expired entry is null", registry.get("host"), is(nullValue()));

        HttpChaosProfile partial = httpChaosProfile().withErrorProbability(0.3);
        HttpChaosProfile result = registry.patch("host", partial);

        assertThat("patched onto expired = new registration", result, is(notNullValue()));
        assertThat("new profile has patched field", result.getErrorProbability(), is(0.3));
        assertThat("new profile does not carry old errorStatus", result.getErrorStatus(), is(nullValue()));
    }

    @Test
    public void shouldPatchMultipleFieldsAtOnce() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile base = httpChaosProfile()
            .withErrorProbability(0.5)
            .withDropConnectionProbability(0.1)
            .withErrorStatus(503)
            .withSeed(42L);
        registry.put("host", base);

        HttpChaosProfile partial = httpChaosProfile()
            .withErrorProbability(0.9)
            .withErrorStatus(429);
        registry.patch("host", partial);

        HttpChaosProfile result = registry.get("host");
        assertThat("first patched field", result.getErrorProbability(), is(0.9));
        assertThat("second patched field", result.getErrorStatus(), is(429));
        assertThat("unpatched field preserved", result.getDropConnectionProbability(), is(0.1));
        assertThat("unpatched field preserved", result.getSeed(), is(42L));
    }

    @Test
    public void shouldNormalizePatchedHostCaseInsensitively() {
        ServiceChaosRegistry registry = new ServiceChaosRegistry(() -> System.currentTimeMillis());
        HttpChaosProfile base = httpChaosProfile().withErrorProbability(0.5);
        registry.put("Example.COM", base);

        HttpChaosProfile partial = httpChaosProfile().withErrorProbability(0.8);
        registry.patch("EXAMPLE.com", partial);

        HttpChaosProfile result = registry.get("example.com");
        assertThat("case-insensitive patch applied", result.getErrorProbability(), is(0.8));
    }
}
