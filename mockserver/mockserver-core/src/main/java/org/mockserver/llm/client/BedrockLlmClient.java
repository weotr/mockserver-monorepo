package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime client for Amazon Bedrock's Anthropic models
 * ({@code POST /model/{modelId}/invoke}). The request body and response shape
 * are Anthropic's (so response parsing is inherited from
 * {@link AnthropicLlmClient}), wrapped with the Bedrock
 * {@code anthropic_version} field.
 * <p>
 * <strong>Auth — SigV4 signing:</strong> When {@link LlmBackend#apiKey()} is set
 * in the format {@code accessKeyId:secretAccessKey} (optionally
 * {@code accessKeyId:secretAccessKey:sessionToken} for STS temporary credentials),
 * this client automatically signs the request with AWS Signature Version 4 using
 * JDK crypto only (SHA-256, HmacSHA256). The region is parsed from the
 * {@code baseUrl} host ({@code bedrock-runtime.<region>.amazonaws.com}) and
 * defaults to {@code us-east-1} if not parseable. The service is {@code bedrock}.
 * <p>
 * <strong>Headers escape hatch:</strong> The {@link LlmBackend#headers()} escape
 * hatch remains supported for pre-signed / signing-proxy setups. When SigV4 creds
 * are present, the auto-generated {@code Authorization} header takes precedence
 * over any {@code Authorization} supplied via the escape hatch (the SigV4 header
 * is applied after the escape-hatch headers). When no creds are present, the
 * escape hatch is the only source of auth — backward compatible with the original
 * behaviour.
 */
public class BedrockLlmClient extends AnthropicLlmClient {

    static final String BEDROCK_ANTHROPIC_VERSION = "bedrock-2023-05-31";
    static final String DEFAULT_MODEL = "anthropic.claude-3-5-sonnet-20241022-v2:0";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String SERVICE = "bedrock";

    /**
     * Pattern to extract the region from a Bedrock runtime endpoint hostname.
     * Matches {@code bedrock-runtime.<region>.amazonaws.com}.
     */
    private static final Pattern BEDROCK_HOST_REGION_PATTERN =
        Pattern.compile("bedrock-runtime\\.([a-z0-9-]+)\\.amazonaws\\.com");

    @Override
    public Provider provider() {
        return Provider.BEDROCK;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        // baseUrl is the regional endpoint, e.g. https://bedrock-runtime.us-east-1.amazonaws.com
        String baseUrl = resolveBaseUrl(backend, "https://bedrock-runtime.us-east-1.amazonaws.com");
        String modelId = resolveModel(backend, DEFAULT_MODEL);

        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("anthropic_version", BEDROCK_ANTHROPIC_VERSION);
        body.put("max_tokens", DEFAULT_MAX_TOKENS);
        body.put("temperature", 0);

        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        for (ParsedMessage message : prompt.getMessages()) {
            String text = message.getTextContent();
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (message.getRole() == ParsedMessage.Role.SYSTEM) {
                if (system.length() > 0) {
                    system.append("\n");
                }
                system.append(text);
            } else {
                ObjectNode messageNode = messages.addObject();
                messageNode.put("role", message.getRole() == ParsedMessage.Role.ASSISTANT ? "assistant" : "user");
                messageNode.put("content", text);
            }
        }
        if (system.length() > 0) {
            body.put("system", system.toString());
        }

        String jsonBody = writeJson(body);

        // Build the base request (escape-hatch headers applied inside postJson)
        HttpRequest request = postJson(backend, baseUrl, "/model/" + modelId + "/invoke", jsonBody);

        // SigV4 signing: parse creds from apiKey in format akid:secret[:token]
        AwsCredentials creds = parseAwsCredentials(backend.apiKey());
        if (creds != null) {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            String region = parseRegion(host);

            // Canonical URI: encode each path segment per SigV4 rules.
            // The path is /model/{modelId}/invoke — modelId may contain ':' and '.'
            String canonicalUri = "/model/" + AwsSigV4Signer.uriEncodePathSegment(modelId) + "/invoke";

            byte[] payloadBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

            Map<String, String> sigHeaders = AwsSigV4Signer.sign(
                "POST",
                host,
                canonicalUri,
                "",  // no query string
                payloadBytes,
                region,
                SERVICE,
                creds.accessKeyId,
                creds.secretAccessKey,
                creds.sessionToken,
                signingTimestamp(),
                Collections.singletonMap("content-type", "application/json")
            );

            // Apply SigV4 headers — these override any same-named headers from
            // the escape hatch (applied earlier in postJson), so SigV4 takes precedence.
            for (Map.Entry<String, String> entry : sigHeaders.entrySet()) {
                request.replaceHeader(new Header(entry.getKey(), entry.getValue()));
            }
        }

        return request;
    }

    /**
     * Signing timestamp. Overridable for testing (pin the clock to produce
     * deterministic signatures). The default returns {@link Instant#now()}.
     */
    protected Instant signingTimestamp() {
        return Instant.now();
    }

    /**
     * Parse AWS credentials from an apiKey string. The expected format is
     * {@code accessKeyId:secretAccessKey} or {@code accessKeyId:secretAccessKey:sessionToken}.
     *
     * @return parsed credentials, or {@code null} if the apiKey is null, blank,
     *         or does not contain at least one ':' separator.
     */
    static AwsCredentials parseAwsCredentials(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        int firstColon = apiKey.indexOf(':');
        if (firstColon < 0) {
            return null; // not in akid:secret format — skip signing
        }
        String accessKeyId = apiKey.substring(0, firstColon);
        String rest = apiKey.substring(firstColon + 1);
        int secondColon = rest.indexOf(':');
        if (secondColon < 0) {
            return new AwsCredentials(accessKeyId, rest, null);
        } else {
            return new AwsCredentials(
                accessKeyId,
                rest.substring(0, secondColon),
                rest.substring(secondColon + 1)
            );
        }
    }

    /**
     * Extract the AWS region from a Bedrock runtime host. Falls back to
     * {@code us-east-1} if the host does not match the expected pattern.
     */
    static String parseRegion(String host) {
        if (host == null) {
            return DEFAULT_REGION;
        }
        Matcher matcher = BEDROCK_HOST_REGION_PATTERN.matcher(host);
        return matcher.matches() ? matcher.group(1) : DEFAULT_REGION;
    }

    /**
     * Parsed AWS credentials tuple.
     */
    static final class AwsCredentials {
        final String accessKeyId;
        final String secretAccessKey;
        final String sessionToken; // nullable

        AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.sessionToken = sessionToken;
        }
    }
}
