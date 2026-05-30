package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.model.HttpChaosProfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;

public class ServiceChaosRegistryTest {

    @After
    public void cleanup() {
        ServiceChaosRegistry.getInstance().reset();
    }

    @Test
    public void shouldRegisterAndResolveByHost() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0);
        ServiceChaosRegistry.getInstance().put("upstream.svc", profile);
        assertThat(ServiceChaosRegistry.getInstance().get("upstream.svc"), is(profile));
    }

    @Test
    public void shouldMatchHostCaseInsensitivelyAndIgnorePort() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        ServiceChaosRegistry.getInstance().put("Upstream.SVC", profile);
        assertThat("lower-cased lookup", ServiceChaosRegistry.getInstance().get("upstream.svc"), is(profile));
        assertThat("lookup with port", ServiceChaosRegistry.getInstance().get("upstream.svc:8080"), is(profile));
    }

    @Test
    public void shouldRegisterWithPortAndResolveWithoutPort() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        ServiceChaosRegistry.getInstance().put("upstream.svc:8080", profile);
        assertThat(ServiceChaosRegistry.getInstance().get("upstream.svc"), is(profile));
    }

    @Test
    public void shouldHandleBracketedIpv6Host() {
        HttpChaosProfile profile = httpChaosProfile().withErrorStatus(503);
        ServiceChaosRegistry.getInstance().put("[::1]:8080", profile);
        assertThat("lookup without port", ServiceChaosRegistry.getInstance().get("[::1]"), is(profile));
        assertThat("lookup with a different port", ServiceChaosRegistry.getInstance().get("[::1]:9999"), is(profile));
    }

    @Test
    public void shouldReturnNullForUnknownHost() {
        assertThat(ServiceChaosRegistry.getInstance().get("nope"), is(nullValue()));
        assertThat(ServiceChaosRegistry.getInstance().get(null), is(nullValue()));
    }

    @Test
    public void shouldIgnoreNullProfileOrHost() {
        ServiceChaosRegistry.getInstance().put(null, httpChaosProfile());
        ServiceChaosRegistry.getInstance().put("h", null);
        assertThat(ServiceChaosRegistry.getInstance().get("h"), is(nullValue()));
    }

    @Test
    public void shouldRemoveHost() {
        ServiceChaosRegistry.getInstance().put("upstream.svc", httpChaosProfile().withErrorStatus(503));
        ServiceChaosRegistry.getInstance().remove("UPSTREAM.svc:9999");
        assertThat(ServiceChaosRegistry.getInstance().get("upstream.svc"), is(nullValue()));
    }

    @Test
    public void shouldClearAllOnReset() {
        ServiceChaosRegistry.getInstance().put("a", httpChaosProfile());
        ServiceChaosRegistry.getInstance().put("b", httpChaosProfile());
        ServiceChaosRegistry.getInstance().reset();
        assertThat(ServiceChaosRegistry.getInstance().entries().isEmpty(), is(true));
    }
}
