package org.mockserver.validator.jsonschema;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaValidator.JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY;

/**
 * Behavioural tests for WS6.4 — JSON Schema remote {@code $ref} control.
 * <p>
 * SECURITY: by default a body-schema must NOT be able to trigger an outbound fetch
 * (SSRF) or an external file read via a remote {@code $ref}. Schemas using only
 * internal/inline refs must validate exactly as before. Setting the opt-in system
 * property restores remote-ref resolution.
 * <p>
 * These tests must never actually hit the network — the assertions verify the
 * control path (block / opt-in) without depending on any external resource being
 * reachable. The blocked-ref URL points at an unroutable host so that, were the
 * control absent, the test would fail rather than silently pass.
 *
 * @author jamesdbloom
 */
public class JsonSchemaValidatorRemoteRefTest {

    // RFC 5737 TEST-NET-1 — guaranteed non-routable; never actually contacted because the ref is blocked.
    private static final String REMOTE_REF_SCHEMA = "{" + NEW_LINE +
        "    \"type\": \"object\"," + NEW_LINE +
        "    \"properties\": {" + NEW_LINE +
        "        \"value\": { \"$ref\": \"http://192.0.2.1/external-schema.json\" }" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"required\": [ \"value\" ]" + NEW_LINE +
        "}";

    // internal/inline-only refs — the common case — must be unaffected by the control.
    private static final String INTERNAL_REF_SCHEMA = "{" + NEW_LINE +
        "    \"type\": \"object\"," + NEW_LINE +
        "    \"definitions\": {" + NEW_LINE +
        "        \"positiveInt\": { \"type\": \"integer\", \"minimum\": 1 }" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"properties\": {" + NEW_LINE +
        "        \"count\": { \"$ref\": \"#/definitions/positiveInt\" }" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"required\": [ \"count\" ]" + NEW_LINE +
        "}";

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private String previousPropertyValue;

    @Before
    public void recordProperty() {
        previousPropertyValue = System.getProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY);
    }

    @After
    public void restoreProperty() {
        if (previousPropertyValue == null) {
            System.clearProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY);
        } else {
            System.setProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY, previousPropertyValue);
        }
    }

    @Test
    public void shouldMatchSchemaUsingOnlyInternalRefsAsBefore() {
        // given a schema with only internal ($ref: #/...) refs — the common case
        System.clearProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY);

        // then valid body matches with no errors
        assertThat(new JsonSchemaValidator(mockServerLogger, INTERNAL_REF_SCHEMA).isValid("{ \"count\": 3 }"), is(""));
    }

    @Test
    public void shouldRejectNonMatchingBodyAgainstInternalRefSchemaAsBefore() {
        // given
        System.clearProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY);

        // then a body violating the internal ref ($ref resolves to minimum:1) is reported as a non-match
        String result = new JsonSchemaValidator(mockServerLogger, INTERNAL_REF_SCHEMA).isValid("{ \"count\": 0 }");
        assertThat(result, not(is("")));
        assertThat(result, containsString("count"));
    }

    @Test
    public void shouldBlockRemoteRefByDefaultWithoutNetworkFetch() {
        // given a schema with a remote http $ref and the secure default (no opt-in)
        System.clearProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY);

        // when validating a body against it
        long start = System.currentTimeMillis();
        String result = new JsonSchemaValidator(mockServerLogger, REMOTE_REF_SCHEMA).isValid("{ \"value\": 123 }");
        long elapsed = System.currentTimeMillis() - start;

        // then the remote $ref is not fetched: it is handled safely (blocked / unresolved),
        // returns promptly rather than hanging on a network timeout, and never raises a
        // connection failure that would prove an outbound attempt was made
        assertThat("remote $ref must not trigger a network fetch (would hang/timeout)", elapsed < 5000L, is(true));
        assertThat(result, not(containsString("ConnectException")));
        assertThat(result, not(containsString("UnknownHostException")));
        assertThat(result, not(containsString("SocketTimeoutException")));
    }

    @Test
    public void shouldExposeRemoteRefsBlockedByDefault() {
        // given no opt-in
        System.clearProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY);

        // then remote refs are blocked
        assertThat(JsonSchemaValidator.isRemoteRefsAllowed(), is(false));
    }

    @Test
    public void shouldAllowRemoteRefsWhenSystemPropertyOptIn() {
        // given the opt-in system property is set
        System.setProperty(JSON_SCHEMA_ALLOW_REMOTE_REFS_PROPERTY, "true");

        // then the validator build-time flag reflects the opt-in, restoring remote-ref resolution.
        // (We assert the config flag effect rather than performing a real network fetch.)
        assertThat(JsonSchemaValidator.isRemoteRefsAllowed(), is(true));

        // and a schema that uses only internal refs continues to validate normally under opt-in
        assertThat(new JsonSchemaValidator(mockServerLogger, INTERNAL_REF_SCHEMA).isValid("{ \"count\": 3 }"), is(""));
    }
}
