package org.mockserver.state.infinispan;

import org.jgroups.JChannel;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that the jgroups-kubernetes.xml stack file:
 * <ol>
 *   <li>Exists on the classpath (shipped in the module's resources)</li>
 *   <li>Parses as valid JGroups configuration</li>
 *   <li>Uses TCP transport (not UDP — cloud/k8s compatible)</li>
 *   <li>Uses dns.DNS_PING for discovery (Kubernetes headless Service)</li>
 * </ol>
 * <p>
 * This test does NOT start a JGroups channel (no network I/O) — it only
 * validates the XML structure. A full cluster-formation test requires
 * multiple pods or containers.
 */
class JGroupsKubernetesStackTest {

    private static final String STACK_RESOURCE = "jgroups-kubernetes.xml";

    @Test
    void shouldExistOnClasspath() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(STACK_RESOURCE)) {
            assertNotNull(is, "jgroups-kubernetes.xml must be on the classpath");
        } catch (Exception e) {
            fail("Failed to read " + STACK_RESOURCE + " from classpath: " + e.getMessage());
        }
    }

    @Test
    void shouldParseAsValidJGroupsConfiguration() throws Exception {
        ProtocolStackConfigurator configurator = ConfiguratorFactory.getStackConfigurator(STACK_RESOURCE);
        assertNotNull(configurator, "ConfiguratorFactory should parse " + STACK_RESOURCE);

        List<ProtocolConfiguration> protocols = configurator.getProtocolStack();
        assertThat("Stack should have protocols", protocols, is(not(empty())));
    }

    @Test
    void shouldUseTcpTransport() throws Exception {
        ProtocolStackConfigurator configurator = ConfiguratorFactory.getStackConfigurator(STACK_RESOURCE);
        List<ProtocolConfiguration> protocols = configurator.getProtocolStack();

        // First protocol in the stack is the transport
        String transportName = protocols.get(0).getProtocolName();
        assertThat("Transport must be TCP for Kubernetes compatibility",
            transportName, equalTo("TCP"));
    }

    @Test
    void shouldUseDnsPingDiscovery() throws Exception {
        ProtocolStackConfigurator configurator = ConfiguratorFactory.getStackConfigurator(STACK_RESOURCE);
        List<ProtocolConfiguration> protocols = configurator.getProtocolStack();

        boolean hasDnsPing = protocols.stream()
            .anyMatch(p -> p.getProtocolName().equals("dns.DNS_PING"));
        assertTrue(hasDnsPing, "Stack must include dns.DNS_PING for Kubernetes pod discovery");
    }

    @Test
    void shouldHaveDnsQueryProperty() throws Exception {
        ProtocolStackConfigurator configurator = ConfiguratorFactory.getStackConfigurator(STACK_RESOURCE);
        List<ProtocolConfiguration> protocols = configurator.getProtocolStack();

        ProtocolConfiguration dnsPing = protocols.stream()
            .filter(p -> p.getProtocolName().equals("dns.DNS_PING"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("dns.DNS_PING not found"));

        String dnsQuery = dnsPing.getProperties().get("dns_query");
        assertNotNull(dnsQuery, "dns.DNS_PING must have a dns_query property");
        // The value references the JGROUPS_DNS_QUERY env var
        assertThat(dnsQuery, containsString("JGROUPS_DNS_QUERY"));
    }

    @Test
    void loopbackStackShouldAlsoExistOnClasspath() {
        // Sanity check: the loopback stack (for embedded tests) must still be present
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("jgroups-loopback.xml")) {
            assertNotNull(is, "jgroups-loopback.xml must remain on the classpath");
        } catch (Exception e) {
            fail("Failed to read jgroups-loopback.xml from classpath: " + e.getMessage());
        }
    }
}
