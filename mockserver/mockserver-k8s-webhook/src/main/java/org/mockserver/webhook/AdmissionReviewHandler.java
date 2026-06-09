package org.mockserver.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;

/**
 * Handles Kubernetes MutatingAdmissionWebhook requests (admission.k8s.io/v1).
 * <p>
 * Given an AdmissionReview JSON for a Pod CREATE, returns an AdmissionResponse
 * with a base64-encoded JSONPatch that injects the MockServer sidecar container
 * and iptables init container — but only when the pod opts in via annotation
 * and has not already been injected.
 * <p>
 * This handler is stateless and safe to call concurrently.
 */
public class AdmissionReviewHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdmissionReviewHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMISSION_API_VERSION = "admission.k8s.io/v1";
    private static final String ADMISSION_KIND = "AdmissionReview";
    private static final String PATCH_TYPE = "JSONPatch";

    private final SidecarPatchBuilder patchBuilder;

    public AdmissionReviewHandler(SidecarInjectionConfig config) {
        this.patchBuilder = new SidecarPatchBuilder(config);
    }

    /**
     * Processes an AdmissionReview request and returns the AdmissionReview response JSON.
     *
     * @param requestBody the raw JSON bytes of the AdmissionReview request
     * @return the AdmissionReview response JSON bytes
     * @throws IOException if the request body cannot be parsed
     */
    public byte[] handleAdmissionReview(byte[] requestBody) throws IOException {
        JsonNode request = MAPPER.readTree(requestBody);
        return handleAdmissionReview(request);
    }

    /**
     * Processes an AdmissionReview request and returns the AdmissionReview response JSON bytes.
     *
     * @param request the parsed AdmissionReview request
     * @return the AdmissionReview response JSON bytes
     * @throws IOException if response serialisation fails
     */
    public byte[] handleAdmissionReview(JsonNode request) throws IOException {
        JsonNode reviewRequest = request.path("request");
        String uid = reviewRequest.path("uid").asText("");

        LOG.info("processing admission review, uid={}", uid);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("apiVersion", ADMISSION_API_VERSION);
        response.put("kind", ADMISSION_KIND);

        ObjectNode admissionResponse = MAPPER.createObjectNode();
        admissionResponse.put("uid", uid);
        admissionResponse.put("allowed", true);

        // Extract the pod object
        JsonNode podObject = reviewRequest.path("object");

        if (patchBuilder.shouldInject(podObject)) {
            ArrayNode patch = patchBuilder.buildPatch(podObject);
            String patchJson = MAPPER.writeValueAsString(patch);
            String patchBase64 = Base64.getEncoder().encodeToString(patchJson.getBytes());

            admissionResponse.put("patchType", PATCH_TYPE);
            admissionResponse.put("patch", patchBase64);

            LOG.info("injection patch applied for uid={}", uid);
        } else {
            LOG.info("no injection needed for uid={}", uid);
        }

        response.set("response", admissionResponse);
        return MAPPER.writeValueAsBytes(response);
    }

    /**
     * Creates an AdmissionReview response that allows the request without any patch.
     * Used for non-CREATE operations or error fallback.
     *
     * @param uid the request UID
     * @return the allow-only response JSON bytes
     * @throws IOException if serialisation fails
     */
    public byte[] createAllowResponse(String uid) throws IOException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("apiVersion", ADMISSION_API_VERSION);
        response.put("kind", ADMISSION_KIND);

        ObjectNode admissionResponse = MAPPER.createObjectNode();
        admissionResponse.put("uid", uid);
        admissionResponse.put("allowed", true);

        response.set("response", admissionResponse);
        return MAPPER.writeValueAsBytes(response);
    }

    /**
     * Creates an AdmissionReview response that allows the request but includes
     * a status message explaining why no mutation was performed.
     *
     * @param uid     the request UID
     * @param message the status message
     * @return the response JSON bytes
     * @throws IOException if serialisation fails
     */
    public byte[] createAllowWithMessage(String uid, String message) throws IOException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("apiVersion", ADMISSION_API_VERSION);
        response.put("kind", ADMISSION_KIND);

        ObjectNode admissionResponse = MAPPER.createObjectNode();
        admissionResponse.put("uid", uid);
        admissionResponse.put("allowed", true);

        ObjectNode status = MAPPER.createObjectNode();
        status.put("message", message);
        admissionResponse.set("status", status);

        response.set("response", admissionResponse);
        return MAPPER.writeValueAsBytes(response);
    }
}
