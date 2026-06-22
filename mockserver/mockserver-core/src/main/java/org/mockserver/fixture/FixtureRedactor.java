package org.mockserver.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.*;

/**
 * Masks sensitive data in recorded expectations before they are written to fixture files.
 * <p>
 * Operates on copies: the live event log is never mutated. Header values for a configurable
 * set of header names are replaced with a placeholder ({@value REDACTED_PLACEHOLDER}).
 * <p>
 * Default sensitive headers: {@code Authorization}, {@code x-api-key}, {@code api-key},
 * {@code Cookie}, {@code Set-Cookie}, {@code Proxy-Authorization}.
 */
public class FixtureRedactor {

    public static final String REDACTED_PLACEHOLDER = "***REDACTED***";

    private static final Set<String> DEFAULT_SENSITIVE_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        DEFAULT_SENSITIVE_HEADERS.add("Authorization");
        DEFAULT_SENSITIVE_HEADERS.add("x-api-key");
        DEFAULT_SENSITIVE_HEADERS.add("api-key");
        DEFAULT_SENSITIVE_HEADERS.add("Cookie");
        DEFAULT_SENSITIVE_HEADERS.add("Set-Cookie");
        DEFAULT_SENSITIVE_HEADERS.add("Proxy-Authorization");
    }

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    /**
     * The default sensitive header names (case-insensitive), as an unmodifiable
     * set, so callers can reuse them when constructing a redactor with additional
     * body fields without re-declaring the list.
     */
    public static Set<String> defaultSensitiveHeaders() {
        return Collections.unmodifiableSet(DEFAULT_SENSITIVE_HEADERS);
    }

    private final Set<String> sensitiveHeaders;
    private final Set<String> sensitiveBodyFields;

    /**
     * Create a redactor with the default sensitive header list and no body-field
     * redaction.
     */
    public FixtureRedactor() {
        this.sensitiveHeaders = DEFAULT_SENSITIVE_HEADERS;
        this.sensitiveBodyFields = Collections.emptySet();
    }

    /**
     * Create a redactor with a custom sensitive header list and no body-field
     * redaction.
     *
     * @param sensitiveHeaders header names to redact (case-insensitive)
     */
    public FixtureRedactor(Collection<String> sensitiveHeaders) {
        this(sensitiveHeaders, Collections.emptyList());
    }

    /**
     * Create a redactor with custom sensitive headers and JSON body field names.
     * Body fields are matched case-insensitively at any depth of a JSON
     * request/response body; their values are replaced with the placeholder.
     *
     * @param sensitiveHeaders    header names to redact (case-insensitive)
     * @param sensitiveBodyFields JSON field names to redact in bodies (case-insensitive)
     */
    public FixtureRedactor(Collection<String> sensitiveHeaders, Collection<String> sensitiveBodyFields) {
        this.sensitiveHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sensitiveHeaders != null) {
            this.sensitiveHeaders.addAll(sensitiveHeaders);
        }
        this.sensitiveBodyFields = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sensitiveBodyFields != null) {
            this.sensitiveBodyFields.addAll(sensitiveBodyFields);
        }
    }

    /**
     * Redact sensitive headers in an array of expectations. Returns new Expectation objects;
     * the originals are not modified.
     * <p>
     * The {@code Times} / {@code TimeToLive} of the result default to unlimited and the
     * expectation {@code id} is dropped — appropriate for the fixture export/import use
     * case where redacted expectations are re-imported as fresh, unlimited mocks. Use
     * {@link #redact(Expectation[], boolean)} with {@code preserveConstraints=true} to keep
     * the original replay constraints and id (e.g. on the recorded-expectation path).
     *
     * @param expectations the expectations to redact
     * @return new expectations with sensitive header values replaced
     */
    public Expectation[] redact(Expectation[] expectations) {
        return redact(expectations, false);
    }

    /**
     * Redact sensitive headers in an array of expectations. Returns new Expectation objects;
     * the originals are not modified.
     *
     * @param expectations        the expectations to redact
     * @param preserveConstraints when {@code true}, copy {@code Times}, {@code TimeToLive},
     *                            {@code priority} and {@code id} from each source expectation
     *                            into its redacted result; when {@code false}, default to
     *                            unlimited {@code Times} / {@code TimeToLive} and drop the id
     *                            (original fixture export/import behaviour)
     * @return new expectations with sensitive header values replaced
     */
    public Expectation[] redact(Expectation[] expectations, boolean preserveConstraints) {
        if (expectations == null) {
            return new Expectation[0];
        }
        Expectation[] result = new Expectation[expectations.length];
        for (int i = 0; i < expectations.length; i++) {
            result[i] = redactExpectation(expectations[i], preserveConstraints);
        }
        return result;
    }

    private Expectation redactExpectation(Expectation expectation) {
        return redactExpectation(expectation, false);
    }

    private Expectation redactExpectation(Expectation expectation, boolean preserveConstraints) {
        RequestDefinition requestDef = expectation.getHttpRequest();
        HttpResponse response = expectation.getHttpResponse();
        HttpSseResponse sseResponse = expectation.getHttpSseResponse();

        RequestDefinition redactedRequestDef = requestDef;
        if (requestDef instanceof HttpRequest) {
            redactedRequestDef = redactRequest((HttpRequest) requestDef);
        }
        HttpResponse redactedResponse = response != null ? redactResponse(response) : null;
        HttpSseResponse redactedSseResponse = sseResponse != null ? redactSseResponse(sseResponse) : null;

        Expectation result = new Expectation(
            redactedRequestDef,
            preserveConstraints ? expectation.getTimes() : Times.unlimited(),
            preserveConstraints ? expectation.getTimeToLive() : TimeToLive.unlimited(),
            expectation.getPriority()
        );

        if (preserveConstraints && expectation.getId() != null) {
            result.withId(expectation.getId());
        }

        // Scenario state carries no sensitive data — it is matching metadata (e.g. Pact
        // provider-state preconditions). Preserve it across redaction so a state-gated
        // expectation keeps gating after import.
        result
            .withScenarioName(expectation.getScenarioName())
            .withScenarioState(expectation.getScenarioState())
            .withNewScenarioState(expectation.getNewScenarioState());

        if (redactedSseResponse != null) {
            result.thenRespondWithSse(redactedSseResponse);
        } else if (redactedResponse != null) {
            result.thenRespond(redactedResponse);
        } else if (response != null) {
            result.thenRespond(response);
        } else if (sseResponse != null) {
            result.thenRespondWithSse(sseResponse);
        }

        return result;
    }

    /**
     * Redact sensitive headers (and configured JSON body fields) in a single
     * request definition, returning a redacted clone. The original is never
     * mutated. Non-{@link HttpRequest} request definitions (e.g. OpenAPI
     * definitions) are returned unchanged.
     * <p>
     * Used by the live event-log / dashboard redaction path so the masked copy
     * is shown without affecting verification, which reads the unredacted
     * request directly.
     *
     * @param requestDefinition the request to redact (may be {@code null})
     * @return a redacted clone, or the original for null / non-HttpRequest inputs
     */
    public RequestDefinition redactRequestDefinition(RequestDefinition requestDefinition) {
        if (requestDefinition instanceof HttpRequest) {
            return redactRequest((HttpRequest) requestDefinition);
        }
        return requestDefinition;
    }

    /**
     * Redact sensitive headers (and configured JSON body fields) in a single
     * response, returning a redacted clone. The original is never mutated.
     *
     * @param response the response to redact (may be {@code null})
     * @return a redacted clone, or {@code null} when {@code response} is null
     */
    public HttpResponse redactResponseObject(HttpResponse response) {
        if (response == null) {
            return null;
        }
        return redactResponse(response);
    }

    private HttpRequest redactRequest(HttpRequest request) {
        HttpRequest redacted = request.clone();
        if (redacted.getHeaderList() != null) {
            Headers headers = new Headers();
            for (Header header : redacted.getHeaderList()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        redactBodyIfNeeded(redacted.getBodyAsString(), redacted::withBody);
        return redacted;
    }

    private HttpResponse redactResponse(HttpResponse response) {
        HttpResponse redacted = response.clone();
        if (redacted.getHeaderList() != null) {
            Headers headers = new Headers();
            for (Header header : redacted.getHeaderList()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        redactBodyIfNeeded(redacted.getBodyAsString(), redacted::withBody);
        return redacted;
    }

    /**
     * If body-field redaction is configured and {@code bodyString} is JSON,
     * redact matching fields and apply the result via {@code setter}. No-op for
     * absent/non-JSON bodies or when no body fields are configured.
     * <p>
     * Note: the redacted body is re-applied as a string body. For recorded
     * fixtures (captured traffic) bodies are already string bodies, so this does
     * not change match semantics. SSE event-data payloads are out of scope —
     * only request/response bodies are redacted, not individual stream chunks.
     */
    private void redactBodyIfNeeded(String bodyString, java.util.function.Consumer<String> setter) {
        if (sensitiveBodyFields.isEmpty() || bodyString == null || bodyString.isEmpty()) {
            return;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(bodyString);
            if (root != null && (root.isObject() || root.isArray())) {
                redactJsonNode(root);
                setter.accept(OBJECT_MAPPER.writeValueAsString(root));
            }
        } catch (Exception e) {
            // not JSON — leave the body unchanged (headers were already redacted)
        }
    }

    /**
     * Recursively replace the value of any field whose name is in
     * {@link #sensitiveBodyFields} with the placeholder.
     */
    private void redactJsonNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<String> names = object.fieldNames();
            List<String> toRedact = new ArrayList<>();
            while (names.hasNext()) {
                String name = names.next();
                if (sensitiveBodyFields.contains(name)) {
                    toRedact.add(name);
                } else {
                    redactJsonNode(object.get(name));
                }
            }
            for (String name : toRedact) {
                object.put(name, REDACTED_PLACEHOLDER);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                redactJsonNode(child);
            }
        }
    }

    private HttpSseResponse redactSseResponse(HttpSseResponse sseResponse) {
        HttpSseResponse redacted = HttpSseResponse.sseResponse();
        redacted.withStatusCode(sseResponse.getStatusCode());
        redacted.withCloseConnection(sseResponse.getCloseConnection());
        if (sseResponse.getEvents() != null) {
            redacted.withEvents(sseResponse.getEvents());
        }
        if (sseResponse.getHeaders() != null) {
            Headers headers = new Headers();
            for (Header header : sseResponse.getHeaders().getEntries()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        return redacted;
    }
}
