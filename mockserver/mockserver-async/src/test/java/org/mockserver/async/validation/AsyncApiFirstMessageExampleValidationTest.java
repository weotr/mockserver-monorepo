package org.mockserver.async.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Test;
import org.mockserver.async.controlplane.AsyncApiControlPlaneImpl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for per-message first-example schema validation at spec load time (G4).
 * <p>
 * Validates that when an AsyncAPI spec is loaded, the first payload example
 * of each channel's message is checked against the message's declared payload
 * schema. Non-conforming examples surface as {@code validationIssues} in the
 * load response and status, without hard-failing the load.
 * <p>
 * Mirrors the existing {@link AsyncApiSchemaValidatorTest} and
 * {@link org.mockserver.async.controlplane.AsyncApiControlPlaneImplTest} patterns.
 */
public class AsyncApiFirstMessageExampleValidationTest {

    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl();

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    // ---- Conforming example: no validation issue ----

    @Test
    public void shouldNotReportIssueWhenFirstExampleConformsToSchema() throws Exception {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Valid Example\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"orderId\": { \"type\": \"integer\" },\n" +
            "              \"name\": { \"type\": \"string\" }\n" +
            "            },\n" +
            "            \"required\": [\"orderId\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"orderId\": 42, \"name\": \"Widget\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        // No validationIssues array when all examples conform
        assertThat(result.has("validationIssues"), is(false));
    }

    @Test
    public void shouldNotReportIssueForConformingV3Example() throws Exception {
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"V3 Valid\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"messages\": {\n" +
            "        \"eventMessage\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"eventType\": { \"type\": \"string\" }\n" +
            "            },\n" +
            "            \"required\": [\"eventType\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"eventType\": \"click\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.has("validationIssues"), is(false));
    }

    // ---- Non-conforming example: validation issue surfaced ----

    @Test
    public void shouldReportIssueWhenFirstExampleViolatesSchema() throws Exception {
        // The schema requires orderId (integer) but the example provides a string
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Bad Example\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"orderId\": { \"type\": \"integer\" }\n" +
            "            },\n" +
            "            \"required\": [\"orderId\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"orderId\": \"not-an-integer\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.has("validationIssues"), is(true));

        JsonNode issues = result.get("validationIssues");
        // Find the first_message_example issue (not the generated_example one)
        boolean foundFirstMessageIssue = false;
        for (JsonNode issue : issues) {
            String context = issue.get("context").asText();
            if (context.startsWith("first_message_example")) {
                assertThat(issue.get("channel").asText(), is("orders"));
                assertThat(issue.get("errors").asText(), is(not(emptyOrNullString())));
                foundFirstMessageIssue = true;
            }
        }
        assertThat("Expected a first_message_example validation issue", foundFirstMessageIssue, is(true));
    }

    @Test
    public void shouldReportIssueForMissingRequiredFieldInExample() throws Exception {
        // Schema requires "name" but the example omits it
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Missing Required\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"users\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"name\": { \"type\": \"string\" },\n" +
            "              \"age\": { \"type\": \"integer\" }\n" +
            "            },\n" +
            "            \"required\": [\"name\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"age\": 30 } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.has("validationIssues"), is(true));

        JsonNode issues = result.get("validationIssues");
        boolean foundIssue = false;
        for (JsonNode issue : issues) {
            if (issue.get("context").asText().startsWith("first_message_example")
                && "users".equals(issue.get("channel").asText())) {
                assertThat(issue.get("errors").asText(), containsString("name"));
                foundIssue = true;
            }
        }
        assertThat("Expected first_message_example issue for missing 'name'", foundIssue, is(true));
    }

    @Test
    public void shouldReportIssueInStatusAfterLoadWithBadExample() throws Exception {
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Status Check\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"metrics\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"value\": { \"type\": \"number\" }\n" +
            "            },\n" +
            "            \"required\": [\"value\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"value\": \"not-a-number\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        controlPlane.load(spec);
        JsonNode status = controlPlane.status();

        assertThat(status.get("loaded").asBoolean(), is(true));
        assertThat(status.has("validationIssues"), is(true));

        JsonNode issues = status.get("validationIssues");
        boolean foundIssue = false;
        for (JsonNode issue : issues) {
            if (issue.get("context").asText().startsWith("first_message_example")
                && "metrics".equals(issue.get("channel").asText())) {
                foundIssue = true;
            }
        }
        assertThat("Expected first_message_example issue in status", foundIssue, is(true));
    }

    // ---- Channels with no schema or no example: skipped cleanly ----

    @Test
    public void shouldSkipValidationWhenNoSchema() throws Exception {
        // Channel with an example but no schema — should not produce an issue
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"No Schema\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"freeform\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"anything\": true } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        // No validation issues because there is no schema to validate against
        assertThat(result.has("validationIssues"), is(false));
    }

    @Test
    public void shouldSkipValidationWhenNoExamples() throws Exception {
        // Channel with a schema but no examples — should not produce an issue
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"No Examples\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"schemaonly\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"id\": { \"type\": \"integer\" }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        // No first_message_example issues (no spec-provided examples to validate)
        // There may be a generated_example issue but no first_message_example issue
        if (result.has("validationIssues")) {
            for (JsonNode issue : result.get("validationIssues")) {
                assertThat("Should not have first_message_example issue when no examples",
                    issue.get("context").asText(), not(startsWith("first_message_example")));
            }
        }
    }

    @Test
    public void shouldSkipValidationWhenNeitherSchemaNorExamples() throws Exception {
        // Completely bare channel — no schema, no examples
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Bare Channel\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"empty\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {}\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.has("validationIssues"), is(false));
    }

    // ---- Multi-message channels (v2 oneOf, v3 multi-message) ----

    @Test
    public void shouldValidateFirstExampleOfEachMessageInMultiMessageChannel() throws Exception {
        // Two messages in a v3 channel: one conforming, one not
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"Multi-Msg\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"events\": {\n" +
            "      \"messages\": {\n" +
            "        \"goodMessage\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"count\": { \"type\": \"integer\" } },\n" +
            "            \"required\": [\"count\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"count\": 10 } }\n" +
            "          ]\n" +
            "        },\n" +
            "        \"badMessage\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"count\": { \"type\": \"integer\" } },\n" +
            "            \"required\": [\"count\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"count\": \"not-a-number\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.has("validationIssues"), is(true));

        JsonNode issues = result.get("validationIssues");
        boolean foundBadMessageIssue = false;
        boolean foundGoodMessageIssue = false;
        for (JsonNode issue : issues) {
            String context = issue.get("context").asText();
            if (context.equals("first_message_example:badMessage")) {
                foundBadMessageIssue = true;
                assertThat(issue.get("channel").asText(), is("events"));
            }
            if (context.equals("first_message_example:goodMessage")) {
                foundGoodMessageIssue = true;
            }
        }
        assertThat("Expected issue for badMessage", foundBadMessageIssue, is(true));
        assertThat("Should NOT have issue for goodMessage", foundGoodMessageIssue, is(false));
    }

    // ---- Load does not hard-fail on validation issue ----

    @Test
    public void shouldNotHardFailLoadOnExampleValidationIssue() throws Exception {
        // Even with a non-conforming example, the spec should load successfully
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Soft Fail\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"ch1\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"active\": { \"type\": \"boolean\" }\n" +
            "            },\n" +
            "            \"required\": [\"active\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"active\": \"yes\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        // Should NOT throw — soft failure
        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        assertThat(result.get("channelCount").asInt(), is(1));
        // Validation issue is present but the load succeeded
        assertThat(result.has("validationIssues"), is(true));
    }

    // ---- Only first example is validated (not subsequent) ----

    @Test
    public void shouldValidateOnlyFirstExampleNotSubsequent() throws Exception {
        // First example conforms, second does not — should NOT report an issue
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"First Only\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"data\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "              \"score\": { \"type\": \"integer\" }\n" +
            "            },\n" +
            "            \"required\": [\"score\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"score\": 100 } },\n" +
            "            { \"payload\": { \"score\": \"bad\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.get("loaded").asBoolean(), is(true));
        // The first example is valid, so no first_message_example issue should appear
        if (result.has("validationIssues")) {
            for (JsonNode issue : result.get("validationIssues")) {
                assertThat("Should not have first_message_example issue when first example is valid",
                    issue.get("context").asText(), not(startsWith("first_message_example")));
            }
        }
    }

    // ---- Named context includes message name when available ----

    @Test
    public void shouldIncludeMessageNameInContextForV3MultiMessage() throws Exception {
        // Multi-message channel preserves message names in context.
        // Single-message channels synthesize a nameless message (backward compat).
        String spec = "{\n" +
            "  \"asyncapi\": \"3.0.0\",\n" +
            "  \"info\": { \"title\": \"Named Msg\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"notifications\": {\n" +
            "      \"messages\": {\n" +
            "        \"alertMessage\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"level\": { \"type\": \"integer\" } },\n" +
            "            \"required\": [\"level\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"level\": \"high\" } }\n" +
            "          ]\n" +
            "        },\n" +
            "        \"infoMessage\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"text\": { \"type\": \"string\" } }\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"text\": \"hello\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.has("validationIssues"), is(true));
        boolean found = false;
        for (JsonNode issue : result.get("validationIssues")) {
            if ("first_message_example:alertMessage".equals(issue.get("context").asText())) {
                assertThat(issue.get("channel").asText(), is("notifications"));
                found = true;
            }
        }
        assertThat("Expected context with message name 'alertMessage'", found, is(true));
    }

    @Test
    public void shouldUsePlainContextForSingleMessageChannel() throws Exception {
        // Single-message channel (v2): message name is null, so context is just "first_message_example"
        String spec = "{\n" +
            "  \"asyncapi\": \"2.6.0\",\n" +
            "  \"info\": { \"title\": \"Single Msg\", \"version\": \"1.0.0\" },\n" +
            "  \"channels\": {\n" +
            "    \"orders\": {\n" +
            "      \"publish\": {\n" +
            "        \"message\": {\n" +
            "          \"payload\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": { \"id\": { \"type\": \"integer\" } },\n" +
            "            \"required\": [\"id\"]\n" +
            "          },\n" +
            "          \"examples\": [\n" +
            "            { \"payload\": { \"id\": \"not-integer\" } }\n" +
            "          ]\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        JsonNode result = controlPlane.load(spec);

        assertThat(result.has("validationIssues"), is(true));
        boolean found = false;
        for (JsonNode issue : result.get("validationIssues")) {
            if ("first_message_example".equals(issue.get("context").asText())) {
                assertThat(issue.get("channel").asText(), is("orders"));
                found = true;
            }
        }
        assertThat("Expected plain 'first_message_example' context for unnamed message", found, is(true));
    }
}
