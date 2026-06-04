package org.mockserver.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SidecarPatchBuilder}.
 * <p>
 * Covers:
 * - Opt-in detection (annotation present/absent/wrong value)
 * - Idempotency (already-injected marker prevents double-inject)
 * - Patch structure: sidecar container, init container, annotations
 * - UID-exclusion loop-avoidance in the iptables init container
 * - Custom configuration (ports, images, UID)
 * - Edge cases: null pod, empty metadata
 */
class SidecarPatchBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SidecarInjectionConfig config;
    private SidecarPatchBuilder builder;

    @BeforeEach
    void setUp() {
        config = new SidecarInjectionConfig();
        builder = new SidecarPatchBuilder(config);
    }

    // ============================================================
    // shouldInject tests
    // ============================================================

    @Nested
    class ShouldInject {

        @Test
        void returnsFalseForNullPod() {
            assertFalse(builder.shouldInject(null));
        }

        @Test
        void returnsFalseForPodWithoutAnnotations() {
            ObjectNode pod = createPod(null, null);
            assertFalse(builder.shouldInject(pod));
        }

        @Test
        void returnsFalseWhenInjectAnnotationMissing() {
            ObjectNode pod = createPodWithAnnotation("other.annotation", "true");
            assertFalse(builder.shouldInject(pod));
        }

        @Test
        void returnsFalseWhenInjectAnnotationIsFalse() {
            ObjectNode pod = createPodWithAnnotation(SidecarInjectionConfig.INJECT_ANNOTATION, "false");
            assertFalse(builder.shouldInject(pod));
        }

        @Test
        void returnsFalseWhenInjectAnnotationIsEmpty() {
            ObjectNode pod = createPodWithAnnotation(SidecarInjectionConfig.INJECT_ANNOTATION, "");
            assertFalse(builder.shouldInject(pod));
        }

        @Test
        void returnsTrueWhenInjectAnnotationIsTrue() {
            ObjectNode pod = createPodWithAnnotation(SidecarInjectionConfig.INJECT_ANNOTATION, "true");
            assertTrue(builder.shouldInject(pod));
        }

        @Test
        void returnsTrueCaseInsensitive() {
            ObjectNode pod = createPodWithAnnotation(SidecarInjectionConfig.INJECT_ANNOTATION, "True");
            assertTrue(builder.shouldInject(pod));
        }

        @Test
        void returnsFalseWhenAlreadyInjected() {
            ObjectNode pod = createPod(null, null);
            ObjectNode annotations = MAPPER.createObjectNode();
            annotations.put(SidecarInjectionConfig.INJECT_ANNOTATION, "true");
            annotations.put(SidecarInjectionConfig.INJECTED_ANNOTATION, "true");
            ((ObjectNode) pod.get("metadata")).set("annotations", annotations);
            assertFalse(builder.shouldInject(pod));
        }

        @Test
        void returnsTrueWhenInjectedAnnotationPresentButNotTrue() {
            ObjectNode pod = createPod(null, null);
            ObjectNode annotations = MAPPER.createObjectNode();
            annotations.put(SidecarInjectionConfig.INJECT_ANNOTATION, "true");
            annotations.put(SidecarInjectionConfig.INJECTED_ANNOTATION, "false");
            ((ObjectNode) pod.get("metadata")).set("annotations", annotations);
            assertTrue(builder.shouldInject(pod));
        }
    }

    // ============================================================
    // buildPatch tests
    // ============================================================

    @Nested
    class BuildPatch {

        @Test
        void patchContainsIdempotencyMarkerAnnotation() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            // The first operation should add the idempotency marker
            boolean hasInjectedAnnotation = false;
            for (JsonNode op : patch) {
                String path = op.get("path").asText();
                if (path.contains("mockserver.org~1injected")) {
                    hasInjectedAnnotation = true;
                    assertThat(op.get("op").asText(), is("add"));
                    assertThat(op.get("value").asText(), is("true"));
                }
            }
            assertTrue(hasInjectedAnnotation, "patch should contain injected annotation");
        }

        @Test
        void patchContainsStatusAnnotation() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            boolean hasStatusAnnotation = false;
            for (JsonNode op : patch) {
                String path = op.get("path").asText();
                if (path.contains("mockserver.org~1status")) {
                    hasStatusAnnotation = true;
                    assertThat(op.get("op").asText(), is("add"));
                    assertThat(op.get("value").asText(), is("injected"));
                }
            }
            assertTrue(hasStatusAnnotation, "patch should contain status annotation");
        }

        @Test
        void patchAddsIptablesInitContainer() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode initContainerOp = findOpByPathSuffix(patch, "initContainers");
            assertNotNull(initContainerOp, "patch should add initContainers");

            // The value should be an array containing the init container (since pod has no existing initContainers)
            JsonNode initContainersValue = initContainerOp.get("value");
            assertTrue(initContainersValue.isArray(), "initContainers value should be an array");

            JsonNode initContainer = initContainersValue.get(0);
            assertThat(initContainer.get("name").asText(), is("mockserver-iptables-init"));
            assertThat(initContainer.get("image").asText(), is("alpine:3.19"));

            // Verify security context
            JsonNode secCtx = initContainer.get("securityContext");
            assertThat(secCtx.get("runAsUser").asInt(), is(0));
            assertThat(secCtx.get("runAsNonRoot").asBoolean(), is(false));

            // Verify capabilities
            JsonNode caps = secCtx.get("capabilities").get("add");
            assertTrue(caps.isArray());
            assertThat(caps.get(0).asText(), is("NET_ADMIN"));
            assertThat(caps.get(1).asText(), is("NET_RAW"));
        }

        @Test
        void iptablesInitContainerHasUidExclusion() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode initContainerOp = findOpByPathSuffix(patch, "initContainers");
            JsonNode initContainer = initContainerOp.get("value").get(0);
            JsonNode command = initContainer.get("command");

            assertThat(command.size(), is(3));
            assertThat(command.get(0).asText(), is("sh"));
            assertThat(command.get(1).asText(), is("-c"));

            String script = command.get(2).asText();
            // UID exclusion MUST come before REDIRECT rules
            int uidExclusionPos = script.indexOf("--uid-owner 65534 -j RETURN");
            int redirectPos = script.indexOf("-j REDIRECT");
            assertTrue(uidExclusionPos > 0, "script should contain UID exclusion rule");
            assertTrue(redirectPos > 0, "script should contain REDIRECT rule");
            assertTrue(uidExclusionPos < redirectPos,
                "UID exclusion must come BEFORE REDIRECT rules to prevent loop");
        }

        @Test
        void iptablesInitContainerRedirectsConfiguredPorts() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode initContainerOp = findOpByPathSuffix(patch, "initContainers");
            JsonNode initContainer = initContainerOp.get("value").get(0);
            String script = initContainer.get("command").get(2).asText();

            assertThat(script, containsString("--dport 80 -j REDIRECT --to-ports 1080"));
            assertThat(script, containsString("--dport 443 -j REDIRECT --to-ports 1080"));
        }

        @Test
        void patchAddsSidecarContainer() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            assertNotNull(sidecarOp, "patch should add sidecar to containers");
            assertThat(sidecarOp.get("op").asText(), is("add"));

            JsonNode sidecar = sidecarOp.get("value");
            assertThat(sidecar.get("name").asText(), is("mockserver-sidecar"));
            assertThat(sidecar.get("image").asText(), is("mockserver/mockserver:mockserver-6.1.1-SNAPSHOT"));
        }

        @Test
        void sidecarHasTransparentProxyEnabled() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            JsonNode sidecar = sidecarOp.get("value");

            // Check env vars
            JsonNode env = sidecar.get("env");
            boolean hasTransparentProxy = false;
            for (JsonNode envVar : env) {
                if ("MOCKSERVER_TRANSPARENT_PROXY_ENABLED".equals(envVar.get("name").asText())) {
                    assertThat(envVar.get("value").asText(), is("true"));
                    hasTransparentProxy = true;
                }
            }
            assertTrue(hasTransparentProxy, "sidecar should have MOCKSERVER_TRANSPARENT_PROXY_ENABLED=true");
        }

        @Test
        void sidecarHasNetAdminCapability() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            JsonNode sidecar = sidecarOp.get("value");

            JsonNode caps = sidecar.get("securityContext").get("capabilities").get("add");
            assertThat(caps.get(0).asText(), is("NET_ADMIN"));
        }

        @Test
        void sidecarRunsAsConfiguredUid() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            JsonNode sidecar = sidecarOp.get("value");

            assertThat(sidecar.get("securityContext").get("runAsUser").asInt(), is(65534));
        }

        @Test
        void sidecarHasProbes() {
            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            JsonNode sidecar = sidecarOp.get("value");

            assertTrue(sidecar.has("readinessProbe"), "sidecar should have readiness probe");
            assertTrue(sidecar.has("livenessProbe"), "sidecar should have liveness probe");

            assertThat(sidecar.get("readinessProbe").get("httpGet").get("path").asText(),
                is("/liveness/probe"));
        }

        @Test
        void appendsToExistingInitContainers() {
            ObjectNode pod = createOptedInPod();
            // Add existing initContainers
            ArrayNode existingInit = MAPPER.createArrayNode();
            ObjectNode existingContainer = MAPPER.createObjectNode();
            existingContainer.put("name", "existing-init");
            existingContainer.put("image", "busybox");
            existingInit.add(existingContainer);
            ((ObjectNode) pod.get("spec")).set("initContainers", existingInit);

            ArrayNode patch = builder.buildPatch(pod);

            // Should append to existing array (path ends with /-)
            JsonNode initOp = findOpByPathSuffix(patch, "initContainers/-");
            assertNotNull(initOp, "should append to existing initContainers with /-");

            // Should be a single container, not an array
            JsonNode value = initOp.get("value");
            assertTrue(value.isObject(), "when appending, value should be a single container object");
            assertThat(value.get("name").asText(), is("mockserver-iptables-init"));
        }

        @Test
        void createsAnnotationsObjectWhenMissing() {
            // Pod with no annotations at all
            ObjectNode pod = MAPPER.createObjectNode();
            ObjectNode metadata = MAPPER.createObjectNode();
            metadata.put("name", "test-pod");
            pod.set("metadata", metadata);
            ObjectNode spec = MAPPER.createObjectNode();
            ArrayNode containers = MAPPER.createArrayNode();
            ObjectNode appContainer = MAPPER.createObjectNode();
            appContainer.put("name", "app");
            appContainer.put("image", "myapp:latest");
            containers.add(appContainer);
            spec.set("containers", containers);
            pod.set("spec", spec);

            ArrayNode patch = builder.buildPatch(pod);

            // Should create the annotations object with both markers
            JsonNode annotationsOp = findOpByPath(patch, "/metadata/annotations");
            assertNotNull(annotationsOp, "should create annotations object");
            assertThat(annotationsOp.get("op").asText(), is("add"));

            JsonNode annotations = annotationsOp.get("value");
            assertTrue(annotations.isObject());
            assertThat(annotations.get("mockserver.org/injected").asText(), is("true"));
            assertThat(annotations.get("mockserver.org/status").asText(), is("injected"));
        }
    }

    // ============================================================
    // Custom configuration tests
    // ============================================================

    @Nested
    class CustomConfig {

        @Test
        void customMockserverImage() {
            config.setMockserverImage("custom/mockserver:latest");
            builder = new SidecarPatchBuilder(config);

            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            assertThat(sidecarOp.get("value").get("image").asText(),
                is("custom/mockserver:latest"));
        }

        @Test
        void customIptablesImage() {
            config.setIptablesImage("custom/iptables:latest");
            builder = new SidecarPatchBuilder(config);

            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            JsonNode initOp = findOpByPathSuffix(patch, "initContainers");
            JsonNode initContainer = initOp.get("value").isArray()
                ? initOp.get("value").get(0)
                : initOp.get("value");
            assertThat(initContainer.get("image").asText(), is("custom/iptables:latest"));
        }

        @Test
        void customPortAndUid() {
            config.setServerPort(8080)
                .setRunAsUser(1000)
                .setRedirectPorts("80,443,8443");
            builder = new SidecarPatchBuilder(config);

            ObjectNode pod = createOptedInPod();
            ArrayNode patch = builder.buildPatch(pod);

            // Check UID exclusion in init container
            JsonNode initOp = findOpByPathSuffix(patch, "initContainers");
            JsonNode initContainer = initOp.get("value").isArray()
                ? initOp.get("value").get(0)
                : initOp.get("value");
            String script = initContainer.get("command").get(2).asText();
            assertThat(script, containsString("--uid-owner 1000 -j RETURN"));
            assertThat(script, containsString("--to-ports 8080"));
            assertThat(script, containsString("--dport 8443"));

            // Check sidecar container port and UID
            JsonNode sidecarOp = findOpByPathSuffix(patch, "containers/-");
            JsonNode sidecar = sidecarOp.get("value");
            assertThat(sidecar.get("securityContext").get("runAsUser").asInt(), is(1000));
            assertThat(sidecar.get("ports").get(0).get("containerPort").asInt(), is(8080));
        }
    }

    // ============================================================
    // Port validation
    // ============================================================

    @Nested
    class PortValidation {

        @Test
        void validPortsAreAccepted() {
            assertDoesNotThrow(() -> SidecarPatchBuilder.validatePort("80"));
            assertDoesNotThrow(() -> SidecarPatchBuilder.validatePort("443"));
            assertDoesNotThrow(() -> SidecarPatchBuilder.validatePort("1"));
            assertDoesNotThrow(() -> SidecarPatchBuilder.validatePort("65535"));
        }

        @Test
        void nonNumericPortThrowsIllegalArgument() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SidecarPatchBuilder.validatePort("abc"));
            assertThat(ex.getMessage(), containsString("not numeric"));
            assertThat(ex.getMessage(), containsString("abc"));
        }

        @Test
        void portWithSpecialCharsThrowsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                () -> SidecarPatchBuilder.validatePort("80;rm -rf /"));
        }

        @Test
        void portZeroThrowsIllegalArgument() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SidecarPatchBuilder.validatePort("0"));
            assertThat(ex.getMessage(), containsString("out of range"));
        }

        @Test
        void portAbove65535ThrowsIllegalArgument() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SidecarPatchBuilder.validatePort("65536"));
            assertThat(ex.getMessage(), containsString("out of range"));
        }

        @Test
        void buildPatchRejectsNonNumericRedirectPorts() {
            config.setRedirectPorts("80,abc,443");
            builder = new SidecarPatchBuilder(config);
            ObjectNode pod = createOptedInPod();
            assertThrows(IllegalArgumentException.class, () -> builder.buildPatch(pod));
        }
    }

    // ============================================================
    // JSON Pointer escaping
    // ============================================================

    @Nested
    class JsonPointerEscaping {

        @Test
        void escapesSlash() {
            assertThat(SidecarPatchBuilder.escapeJsonPointer("mockserver.org/inject"),
                is("mockserver.org~1inject"));
        }

        @Test
        void escapesTilde() {
            assertThat(SidecarPatchBuilder.escapeJsonPointer("key~value"),
                is("key~0value"));
        }

        @Test
        void escapesBoth() {
            assertThat(SidecarPatchBuilder.escapeJsonPointer("a~b/c"),
                is("a~0b~1c"));
        }

        @Test
        void noEscapeNeeded() {
            assertThat(SidecarPatchBuilder.escapeJsonPointer("simple-key"),
                is("simple-key"));
        }
    }

    // ============================================================
    // Init container builder
    // ============================================================

    @Nested
    class IptablesInitContainer {

        @Test
        void containerSpec() {
            ObjectNode container = builder.buildIptablesInitContainer();

            assertThat(container.get("name").asText(), is("mockserver-iptables-init"));
            assertThat(container.get("image").asText(), is("alpine:3.19"));
            assertThat(container.get("securityContext").get("runAsUser").asInt(), is(0));
        }

        @Test
        void scriptOrderIsCorrect() {
            ObjectNode container = builder.buildIptablesInitContainer();
            String script = container.get("command").get(2).asText();

            // set -e must be first
            assertTrue(script.startsWith("set -e\n"), "script should start with set -e");

            // UID exclusion before REDIRECT
            int uidPos = script.indexOf("-I OUTPUT -m owner --uid-owner");
            int redirectPos = script.indexOf("-A OUTPUT -p tcp --dport");
            assertTrue(uidPos < redirectPos,
                "UID exclusion (INSERT) must precede port REDIRECT (APPEND)");
        }
    }

    // ============================================================
    // Sidecar container builder
    // ============================================================

    @Nested
    class SidecarContainer {

        @Test
        void containerSpec() {
            ObjectNode container = builder.buildSidecarContainer();

            assertThat(container.get("name").asText(), is("mockserver-sidecar"));
            assertThat(container.get("imagePullPolicy").asText(), is("IfNotPresent"));
        }

        @Test
        void environmentVariables() {
            ObjectNode container = builder.buildSidecarContainer();
            JsonNode env = container.get("env");

            assertThat(findEnvValue(env, "MOCKSERVER_TRANSPARENT_PROXY_ENABLED"), is("true"));
            assertThat(findEnvValue(env, "MOCKSERVER_LOG_LEVEL"), is("INFO"));
            assertThat(findEnvValue(env, "SERVER_PORT"), is("1080"));
            assertThat(findEnvValue(env, "MOCKSERVER_LIVENESS_HTTP_GET_PATH"), is("/liveness/probe"));
        }

        @Test
        void securityContext() {
            ObjectNode container = builder.buildSidecarContainer();
            JsonNode secCtx = container.get("securityContext");

            assertThat(secCtx.get("runAsUser").asInt(), is(65534));
            assertThat(secCtx.get("readOnlyRootFilesystem").asBoolean(), is(false));
            assertThat(secCtx.get("allowPrivilegeEscalation").asBoolean(), is(false));
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static ObjectNode createPod(String name, String image) {
        ObjectNode pod = MAPPER.createObjectNode();
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("name", name != null ? name : "test-pod");
        pod.set("metadata", metadata);

        ObjectNode spec = MAPPER.createObjectNode();
        ArrayNode containers = MAPPER.createArrayNode();
        ObjectNode container = MAPPER.createObjectNode();
        container.put("name", "app");
        container.put("image", image != null ? image : "myapp:latest");
        containers.add(container);
        spec.set("containers", containers);
        pod.set("spec", spec);

        return pod;
    }

    private static ObjectNode createPodWithAnnotation(String key, String value) {
        ObjectNode pod = createPod(null, null);
        ObjectNode annotations = MAPPER.createObjectNode();
        annotations.put(key, value);
        ((ObjectNode) pod.get("metadata")).set("annotations", annotations);
        return pod;
    }

    private static ObjectNode createOptedInPod() {
        return createPodWithAnnotation(SidecarInjectionConfig.INJECT_ANNOTATION, "true");
    }

    private static JsonNode findOpByPathSuffix(ArrayNode patch, String suffix) {
        for (JsonNode op : patch) {
            if (op.get("path").asText().endsWith(suffix)) {
                return op;
            }
        }
        return null;
    }

    private static JsonNode findOpByPath(ArrayNode patch, String path) {
        for (JsonNode op : patch) {
            if (path.equals(op.get("path").asText())) {
                return op;
            }
        }
        return null;
    }

    private static String findEnvValue(JsonNode envArray, String name) {
        for (JsonNode env : envArray) {
            if (name.equals(env.get("name").asText())) {
                return env.get("value").asText();
            }
        }
        return null;
    }
}
