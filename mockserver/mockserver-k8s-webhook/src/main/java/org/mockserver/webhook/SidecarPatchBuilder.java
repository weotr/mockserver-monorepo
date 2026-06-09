package org.mockserver.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Builds a RFC 6902 JSON Patch that injects the MockServer sidecar container
 * and iptables-REDIRECT init container into a Kubernetes Pod spec.
 * <p>
 * The patch is designed for use in a MutatingAdmissionWebhook response. It adds:
 * <ol>
 *   <li>An init container that sets up iptables REDIRECT rules with UID-based
 *       loop-avoidance (the same pattern used in the Helm chart's sidecar.iptables)</li>
 *   <li>A MockServer sidecar container with transparent proxy enabled</li>
 *   <li>An idempotency marker annotation to prevent double-injection</li>
 * </ol>
 */
public class SidecarPatchBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarPatchBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SidecarInjectionConfig config;

    public SidecarPatchBuilder(SidecarInjectionConfig config) {
        this.config = config;
    }

    /**
     * Determines whether the given pod should be injected with the MockServer sidecar.
     *
     * @param podSpec the pod object from the admission review (the full pod, not just spec)
     * @return true if the pod opts in via annotation and has not already been injected
     */
    public boolean shouldInject(JsonNode podSpec) {
        if (podSpec == null) {
            return false;
        }

        JsonNode metadata = podSpec.path("metadata");
        JsonNode annotations = metadata.path("annotations");

        // Check opt-in annotation
        if (!annotations.has(SidecarInjectionConfig.INJECT_ANNOTATION)) {
            LOG.debug("pod missing inject annotation, skipping");
            return false;
        }
        String injectValue = annotations.get(SidecarInjectionConfig.INJECT_ANNOTATION).asText("");
        if (!SidecarInjectionConfig.INJECT_ENABLED.equalsIgnoreCase(injectValue)) {
            LOG.debug("inject annotation value is '{}', not 'true', skipping", injectValue);
            return false;
        }

        // Check idempotency — already injected?
        if (annotations.has(SidecarInjectionConfig.INJECTED_ANNOTATION)) {
            String injectedValue = annotations.get(SidecarInjectionConfig.INJECTED_ANNOTATION).asText("");
            if (SidecarInjectionConfig.INJECTED_VALUE.equalsIgnoreCase(injectedValue)) {
                LOG.debug("pod already injected (idempotency marker present), skipping");
                return false;
            }
        }

        return true;
    }

    /**
     * Builds a JSON Patch (RFC 6902) array to inject the MockServer sidecar
     * and iptables init container into the given pod.
     *
     * @param podSpec the pod object from the admission review
     * @return a JSON Patch array node
     */
    public ArrayNode buildPatch(JsonNode podSpec) {
        ArrayNode patch = MAPPER.createArrayNode();

        boolean hasAnnotations = podSpec.has("metadata")
            && podSpec.get("metadata").has("annotations")
            && !podSpec.get("metadata").get("annotations").isEmpty();

        boolean hasInitContainers = podSpec.has("spec")
            && podSpec.get("spec").has("initContainers")
            && podSpec.get("spec").get("initContainers").isArray()
            && !podSpec.get("spec").get("initContainers").isEmpty();

        boolean hasContainers = podSpec.has("spec")
            && podSpec.get("spec").has("containers")
            && podSpec.get("spec").get("containers").isArray()
            && !podSpec.get("spec").get("containers").isEmpty();

        // 1. Add idempotency marker annotation
        if (hasAnnotations) {
            patch.add(createAddOp(
                "/metadata/annotations/" + escapeJsonPointer(SidecarInjectionConfig.INJECTED_ANNOTATION),
                SidecarInjectionConfig.INJECTED_VALUE
            ));
            patch.add(createAddOp(
                "/metadata/annotations/" + escapeJsonPointer(SidecarInjectionConfig.STATUS_ANNOTATION),
                "injected"
            ));
        } else {
            // Annotations object doesn't exist — create it
            ObjectNode annotationsNode = MAPPER.createObjectNode();
            annotationsNode.put(SidecarInjectionConfig.INJECTED_ANNOTATION, SidecarInjectionConfig.INJECTED_VALUE);
            annotationsNode.put(SidecarInjectionConfig.STATUS_ANNOTATION, "injected");
            patch.add(createAddOp("/metadata/annotations", annotationsNode));
        }

        // 2. Add iptables init container
        ObjectNode initContainer = buildIptablesInitContainer();
        if (hasInitContainers) {
            // Append to existing initContainers array
            patch.add(createAddOp("/spec/initContainers/-", initContainer));
        } else {
            // Create initContainers array with the init container
            ArrayNode initContainers = MAPPER.createArrayNode();
            initContainers.add(initContainer);
            patch.add(createAddOp("/spec/initContainers", initContainers));
        }

        // 3. Add MockServer sidecar container
        ObjectNode sidecarContainer = buildSidecarContainer();
        if (hasContainers) {
            // Append to existing containers array
            patch.add(createAddOp("/spec/containers/-", sidecarContainer));
        } else {
            // Create containers array (unusual — pod should always have containers)
            ArrayNode containers = MAPPER.createArrayNode();
            containers.add(sidecarContainer);
            patch.add(createAddOp("/spec/containers", containers));
        }

        LOG.info("built injection patch with {} operations", patch.size());
        return patch;
    }

    /**
     * Builds the iptables init container spec.
     * <p>
     * The init container:
     * <ul>
     *   <li>Runs as root with NET_ADMIN + NET_RAW capabilities</li>
     *   <li>Excludes MockServer's UID from REDIRECT (loop avoidance)</li>
     *   <li>Adds REDIRECT rules for each configured port</li>
     * </ul>
     */
    ObjectNode buildIptablesInitContainer() {
        ObjectNode container = MAPPER.createObjectNode();
        container.put("name", "mockserver-iptables-init");
        container.put("image", config.getIptablesImage());

        // Security context: root with NET_ADMIN + NET_RAW
        ObjectNode securityContext = MAPPER.createObjectNode();
        securityContext.put("runAsUser", 0);
        securityContext.put("runAsNonRoot", false);
        ObjectNode capabilities = MAPPER.createObjectNode();
        ArrayNode addCaps = MAPPER.createArrayNode();
        addCaps.add("NET_ADMIN");
        addCaps.add("NET_RAW");
        capabilities.set("add", addCaps);
        securityContext.set("capabilities", capabilities);
        container.set("securityContext", securityContext);

        // Command: iptables rules with UID exclusion + port redirect
        ArrayNode command = MAPPER.createArrayNode();
        command.add("sh");
        command.add("-c");

        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        // Install iptables if not available (alpine needs it)
        script.append("apk add --no-cache iptables > /dev/null 2>&1 || true\n");
        // Exclude MockServer's own egress UID to prevent redirect loop
        script.append(String.format(
            "iptables -t nat -I OUTPUT -m owner --uid-owner %d -j RETURN\n",
            config.getRunAsUser()
        ));
        // Add REDIRECT rules for each port
        for (String port : config.getRedirectPorts().split(",")) {
            String trimmedPort = port.trim();
            if (!trimmedPort.isEmpty()) {
                validatePort(trimmedPort);
                script.append(String.format(
                    "iptables -t nat -A OUTPUT -p tcp --dport %s -j REDIRECT --to-ports %d\n",
                    trimmedPort, config.getServerPort()
                ));
            }
        }
        script.append(String.format(
            "echo \"iptables REDIRECT rules applied for ports %s -> %d (excludeUid=%d)\"\n",
            config.getRedirectPorts(), config.getServerPort(), config.getRunAsUser()
        ));
        command.add(script.toString());
        container.set("command", command);

        return container;
    }

    /**
     * Builds the MockServer sidecar container spec.
     * <p>
     * The sidecar:
     * <ul>
     *   <li>Runs as the configured UID (default 65534)</li>
     *   <li>Has NET_ADMIN capability for SO_ORIGINAL_DST/conntrack</li>
     *   <li>Enables transparent proxy mode via environment variable</li>
     *   <li>Exposes the server port</li>
     * </ul>
     */
    ObjectNode buildSidecarContainer() {
        ObjectNode container = MAPPER.createObjectNode();
        container.put("name", "mockserver-sidecar");
        container.put("image", config.getMockserverImage());
        container.put("imagePullPolicy", "IfNotPresent");

        // Ports
        ArrayNode ports = MAPPER.createArrayNode();
        ObjectNode port = MAPPER.createObjectNode();
        port.put("name", "mockserver");
        port.put("containerPort", config.getServerPort());
        port.put("protocol", "TCP");
        ports.add(port);
        container.set("ports", ports);

        // Environment variables
        ArrayNode env = MAPPER.createArrayNode();
        env.add(createEnvVar("MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "true"));
        env.add(createEnvVar("MOCKSERVER_LOG_LEVEL", config.getLogLevel()));
        env.add(createEnvVar("SERVER_PORT", String.valueOf(config.getServerPort())));
        env.add(createEnvVar("MOCKSERVER_LIVENESS_HTTP_GET_PATH", "/liveness/probe"));
        container.set("env", env);

        // Security context
        ObjectNode securityContext = MAPPER.createObjectNode();
        securityContext.put("runAsUser", config.getRunAsUser());
        securityContext.put("readOnlyRootFilesystem", false);
        securityContext.put("allowPrivilegeEscalation", false);
        ObjectNode capabilities = MAPPER.createObjectNode();
        ArrayNode addCaps = MAPPER.createArrayNode();
        addCaps.add("NET_ADMIN");
        capabilities.set("add", addCaps);
        securityContext.set("capabilities", capabilities);
        container.set("securityContext", securityContext);

        // Readiness probe
        ObjectNode readinessProbe = MAPPER.createObjectNode();
        ObjectNode httpGet = MAPPER.createObjectNode();
        httpGet.put("path", "/liveness/probe");
        httpGet.put("port", config.getServerPort());
        readinessProbe.set("httpGet", httpGet);
        readinessProbe.put("initialDelaySeconds", 2);
        readinessProbe.put("periodSeconds", 2);
        readinessProbe.put("successThreshold", 1);
        readinessProbe.put("failureThreshold", 10);
        container.set("readinessProbe", readinessProbe);

        // Liveness probe
        ObjectNode livenessProbe = MAPPER.createObjectNode();
        ObjectNode livenessHttpGet = MAPPER.createObjectNode();
        livenessHttpGet.put("path", "/liveness/probe");
        livenessHttpGet.put("port", config.getServerPort());
        livenessProbe.set("httpGet", livenessHttpGet);
        livenessProbe.put("initialDelaySeconds", 10);
        livenessProbe.put("periodSeconds", 5);
        livenessProbe.put("successThreshold", 1);
        livenessProbe.put("failureThreshold", 10);
        container.set("livenessProbe", livenessProbe);

        return container;
    }

    private ObjectNode createAddOp(String path, String value) {
        ObjectNode op = MAPPER.createObjectNode();
        op.put("op", "add");
        op.put("path", path);
        op.put("value", value);
        return op;
    }

    private ObjectNode createAddOp(String path, JsonNode value) {
        ObjectNode op = MAPPER.createObjectNode();
        op.put("op", "add");
        op.put("path", path);
        op.set("value", value);
        return op;
    }

    private ObjectNode createEnvVar(String name, String value) {
        ObjectNode envVar = MAPPER.createObjectNode();
        envVar.put("name", name);
        envVar.put("value", value);
        return envVar;
    }

    /**
     * Validates that the given string is a numeric port number (1-65535).
     *
     * @param port the port string to validate
     * @throws IllegalArgumentException if the port is not a valid number
     */
    static void validatePort(String port) {
        try {
            int portNum = Integer.parseInt(port);
            if (portNum < 1 || portNum > 65535) {
                throw new IllegalArgumentException(
                    "redirect port out of range (1-65535): " + port
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "redirect port is not numeric: '" + port + "'", e
            );
        }
    }

    /**
     * Escapes a string for use in a JSON Pointer (RFC 6901).
     * '~' is replaced with '~0', '/' is replaced with '~1'.
     */
    static String escapeJsonPointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
