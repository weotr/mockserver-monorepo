package org.mockserver.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AdmissionReviewHandler}.
 * <p>
 * Covers:
 * - Full AdmissionReview round-trip (request -> response)
 * - Opted-in pod gets patch (with correct base64-encoded JSONPatch)
 * - Non-opted pod is allowed without patch
 * - Already-injected pod is allowed without patch (idempotency)
 * - Allow-only and allow-with-message responses
 * - UID passthrough from request to response
 */
class AdmissionReviewHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AdmissionReviewHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AdmissionReviewHandler(new SidecarInjectionConfig());
    }

    @Nested
    class HandleAdmissionReview {

        @Test
        void optedInPodGetsPatch() throws IOException {
            String reviewJson = buildAdmissionReview("test-uid-1", true, false);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            assertThat(responseNode.get("apiVersion").asText(), is("admission.k8s.io/v1"));
            assertThat(responseNode.get("kind").asText(), is("AdmissionReview"));

            JsonNode admissionResponse = responseNode.get("response");
            assertThat(admissionResponse.get("uid").asText(), is("test-uid-1"));
            assertTrue(admissionResponse.get("allowed").asBoolean());
            assertThat(admissionResponse.get("patchType").asText(), is("JSONPatch"));
            assertTrue(admissionResponse.has("patch"), "response should contain a patch");

            // Decode the patch and verify it's valid JSON
            String patchBase64 = admissionResponse.get("patch").asText();
            byte[] patchBytes = Base64.getDecoder().decode(patchBase64);
            JsonNode patch = MAPPER.readTree(patchBytes);
            assertTrue(patch.isArray(), "decoded patch should be a JSON array");
            assertTrue(patch.size() > 0, "patch should have at least one operation");

            // Verify the patch contains sidecar container addition
            boolean hasSidecar = false;
            for (JsonNode op : patch) {
                if (op.get("path").asText().contains("containers")) {
                    JsonNode value = op.get("value");
                    if (value.isObject() && "mockserver-sidecar".equals(value.path("name").asText())) {
                        hasSidecar = true;
                    }
                }
            }
            assertTrue(hasSidecar, "patch should contain mockserver-sidecar container");
        }

        @Test
        void nonOptedPodIsAllowedWithoutPatch() throws IOException {
            String reviewJson = buildAdmissionReview("test-uid-2", false, false);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            JsonNode admissionResponse = responseNode.get("response");
            assertThat(admissionResponse.get("uid").asText(), is("test-uid-2"));
            assertTrue(admissionResponse.get("allowed").asBoolean());
            assertFalse(admissionResponse.has("patch"), "response should not contain a patch");
            assertFalse(admissionResponse.has("patchType"), "response should not have patchType");
        }

        @Test
        void alreadyInjectedPodIsAllowedWithoutPatch() throws IOException {
            String reviewJson = buildAdmissionReview("test-uid-3", true, true);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            JsonNode admissionResponse = responseNode.get("response");
            assertThat(admissionResponse.get("uid").asText(), is("test-uid-3"));
            assertTrue(admissionResponse.get("allowed").asBoolean());
            assertFalse(admissionResponse.has("patch"), "already-injected pod should not get a patch");
        }

        @Test
        void uidIsPassedThrough() throws IOException {
            String uid = "unique-request-id-12345";
            String reviewJson = buildAdmissionReview(uid, false, false);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            assertThat(responseNode.get("response").get("uid").asText(), is(uid));
        }

        @Test
        void handlesJsonNodeInput() throws IOException {
            String reviewJson = buildAdmissionReview("json-node-uid", true, false);
            JsonNode request = MAPPER.readTree(reviewJson);
            byte[] response = handler.handleAdmissionReview(request);
            JsonNode responseNode = MAPPER.readTree(response);

            assertTrue(responseNode.get("response").get("allowed").asBoolean());
            assertTrue(responseNode.get("response").has("patch"));
        }
    }

    @Nested
    class AllowResponses {

        @Test
        void createAllowResponse() throws IOException {
            byte[] response = handler.createAllowResponse("allow-uid");
            JsonNode responseNode = MAPPER.readTree(response);

            assertThat(responseNode.get("apiVersion").asText(), is("admission.k8s.io/v1"));
            assertThat(responseNode.get("kind").asText(), is("AdmissionReview"));
            assertThat(responseNode.get("response").get("uid").asText(), is("allow-uid"));
            assertTrue(responseNode.get("response").get("allowed").asBoolean());
            assertFalse(responseNode.get("response").has("patch"));
        }

        @Test
        void createAllowWithMessage() throws IOException {
            byte[] response = handler.createAllowWithMessage("msg-uid", "test message");
            JsonNode responseNode = MAPPER.readTree(response);

            assertThat(responseNode.get("response").get("uid").asText(), is("msg-uid"));
            assertTrue(responseNode.get("response").get("allowed").asBoolean());
            assertThat(responseNode.get("response").get("status").get("message").asText(),
                is("test message"));
        }
    }

    @Nested
    class PatchContent {

        @Test
        void patchIsValidBase64EncodedJson() throws IOException {
            String reviewJson = buildAdmissionReview("b64-uid", true, false);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            String patchBase64 = responseNode.get("response").get("patch").asText();

            // Should be valid base64
            assertDoesNotThrow(() -> Base64.getDecoder().decode(patchBase64));

            // Should be valid JSON array
            byte[] decoded = Base64.getDecoder().decode(patchBase64);
            JsonNode patch = assertDoesNotThrow(() -> MAPPER.readTree(decoded));
            assertTrue(patch.isArray());
        }

        @Test
        void patchOperationsHaveCorrectFormat() throws IOException {
            String reviewJson = buildAdmissionReview("format-uid", true, false);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            String patchBase64 = responseNode.get("response").get("patch").asText();
            JsonNode patch = MAPPER.readTree(Base64.getDecoder().decode(patchBase64));

            for (JsonNode op : patch) {
                assertTrue(op.has("op"), "each operation must have 'op' field");
                assertTrue(op.has("path"), "each operation must have 'path' field");
                assertTrue(op.has("value"), "each operation must have 'value' field");
                assertThat(op.get("op").asText(), is("add"));
            }
        }

        @Test
        void patchContainsIptablesInitAndSidecar() throws IOException {
            String reviewJson = buildAdmissionReview("full-patch-uid", true, false);
            byte[] response = handler.handleAdmissionReview(reviewJson.getBytes());
            JsonNode responseNode = MAPPER.readTree(response);

            String patchBase64 = responseNode.get("response").get("patch").asText();
            JsonNode patch = MAPPER.readTree(Base64.getDecoder().decode(patchBase64));

            boolean hasIptablesInit = false;
            boolean hasSidecar = false;
            boolean hasIdempotencyMarker = false;

            for (JsonNode op : patch) {
                String path = op.get("path").asText();
                JsonNode value = op.get("value");

                if (path.contains("initContainers")) {
                    // Could be array or single object
                    if (value.isArray()) {
                        for (JsonNode item : value) {
                            if ("mockserver-iptables-init".equals(item.path("name").asText())) {
                                hasIptablesInit = true;
                            }
                        }
                    } else if ("mockserver-iptables-init".equals(value.path("name").asText())) {
                        hasIptablesInit = true;
                    }
                }

                if (path.contains("containers") && !path.contains("initContainers")) {
                    if (value.isObject() && "mockserver-sidecar".equals(value.path("name").asText())) {
                        hasSidecar = true;
                    }
                }

                if (path.contains("mockserver.org~1injected")) {
                    hasIdempotencyMarker = true;
                }
            }

            assertTrue(hasIptablesInit, "patch should contain iptables init container");
            assertTrue(hasSidecar, "patch should contain sidecar container");
            assertTrue(hasIdempotencyMarker, "patch should contain idempotency marker");
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Builds a minimal AdmissionReview JSON string for testing.
     */
    private String buildAdmissionReview(String uid, boolean optIn, boolean alreadyInjected) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("  \"apiVersion\": \"admission.k8s.io/v1\",");
        sb.append("  \"kind\": \"AdmissionReview\",");
        sb.append("  \"request\": {");
        sb.append("    \"uid\": \"").append(uid).append("\",");
        sb.append("    \"kind\": { \"group\": \"\", \"version\": \"v1\", \"kind\": \"Pod\" },");
        sb.append("    \"resource\": { \"group\": \"\", \"version\": \"v1\", \"resource\": \"pods\" },");
        sb.append("    \"operation\": \"CREATE\",");
        sb.append("    \"object\": {");
        sb.append("      \"apiVersion\": \"v1\",");
        sb.append("      \"kind\": \"Pod\",");
        sb.append("      \"metadata\": {");
        sb.append("        \"name\": \"test-pod\",");
        sb.append("        \"namespace\": \"default\"");
        if (optIn || alreadyInjected) {
            sb.append(",        \"annotations\": {");
            sb.append("          \"mockserver.org/inject\": \"true\"");
            if (alreadyInjected) {
                sb.append(",          \"mockserver.org/injected\": \"true\"");
            }
            sb.append("        }");
        }
        sb.append("      },");
        sb.append("      \"spec\": {");
        sb.append("        \"containers\": [");
        sb.append("          { \"name\": \"app\", \"image\": \"myapp:latest\" }");
        sb.append("        ]");
        sb.append("      }");
        sb.append("    }");
        sb.append("  }");
        sb.append("}");
        return sb.toString();
    }
}
