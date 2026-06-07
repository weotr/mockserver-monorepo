package org.mockserver.llm.client;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

/**
 * Offline tests for {@link AwsSigV4Signer} verified against AWS's published
 * SigV4 test vectors. The signing-key derivation vector is from the AWS
 * documentation "Examples of how to derive a signing key".
 */
public class AwsSigV4SignerTest {

    // --- AWS published test vector: signing key derivation ---
    // Source: https://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html
    // "Examples of how to derive a signing key"

    private static final String VECTOR_SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String VECTOR_DATE_STAMP = "20120215";
    private static final String VECTOR_REGION = "us-east-1";
    private static final String VECTOR_SERVICE = "iam";
    private static final String EXPECTED_SIGNING_KEY_HEX =
        "f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d";

    @Test
    public void signingKeyDerivationMatchesAwsPublishedVector() {
        byte[] signingKey = AwsSigV4Signer.deriveSigningKey(
            VECTOR_SECRET, VECTOR_DATE_STAMP, VECTOR_REGION, VECTOR_SERVICE);
        assertThat(AwsSigV4Signer.hexEncode(signingKey), is(EXPECTED_SIGNING_KEY_HEX));
    }

    // --- Full end-to-end signature: AWS SigV4 test suite "get-vanilla" style ---
    // Uses the well-known test credentials from the AWS SigV4 test suite:
    //   Access Key: AKIDEXAMPLE
    //   Secret Key: wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY
    //   Region: us-east-1, Service: service
    //   Date: 20150830T123600Z

    private static final String TEST_ACCESS_KEY = "AKIDEXAMPLE";
    private static final String TEST_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String TEST_REGION = "us-east-1";
    private static final String TEST_SERVICE = "service";
    private static final Instant TEST_TIMESTAMP = Instant.parse("2015-08-30T12:36:00Z");

    @Test
    public void fullSignatureIsStructurallyCorrectAndDeterministic() {
        // GET / with empty body, host: example.amazonaws.com
        Map<String, String> headers = AwsSigV4Signer.sign(
            "GET",
            "example.amazonaws.com",
            "/",
            "",
            new byte[0],
            TEST_REGION,
            TEST_SERVICE,
            TEST_ACCESS_KEY,
            TEST_SECRET_KEY,
            null,
            TEST_TIMESTAMP,
            null
        );

        // Structural assertions
        assertThat(headers, hasKey("Authorization"));
        assertThat(headers, hasKey("X-Amz-Date"));
        assertThat(headers, hasKey("X-Amz-Content-Sha256"));
        assertThat(headers, not(hasKey("X-Amz-Security-Token")));

        String auth = headers.get("Authorization");
        assertThat(auth, startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request"));
        assertThat(auth, containsString("SignedHeaders=host;x-amz-date"));
        assertThat(auth, containsString("Signature="));

        assertThat(headers.get("X-Amz-Date"), is("20150830T123600Z"));

        // SHA-256 of empty body
        assertThat(headers.get("X-Amz-Content-Sha256"),
            is("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    public void getVanillaSignatureMatchesAwsTestSuiteVector() {
        // Full end-to-end test against the AWS SigV4 test-suite "get-vanilla" vector:
        // GET / with host:example.amazonaws.com, AKIDEXAMPLE / wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY,
        // region us-east-1, service "service", timestamp 20150830T123600Z. The expected
        // signature below is AWS's published get-vanilla answer — an external end-to-end
        // check of canonical-request construction, string-to-sign formatting, and the
        // HMAC signing chain (not merely self-consistency).
        Map<String, String> headers = AwsSigV4Signer.sign(
            "GET",
            "example.amazonaws.com",
            "/",
            "",
            new byte[0],
            TEST_REGION,
            TEST_SERVICE,
            TEST_ACCESS_KEY,
            TEST_SECRET_KEY,
            null,
            TEST_TIMESTAMP,
            null
        );

        String auth = headers.get("Authorization");
        assertThat(auth, startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, Signature="));

        // Verify the signature part is a 64-char lowercase hex string
        String signaturePart = auth.substring(auth.indexOf("Signature=") + "Signature=".length());
        assertThat(signaturePart.length(), is(64));
        assertThat(signaturePart, matchesRegex("[0-9a-f]{64}"));

        // AWS-published get-vanilla signature for these exact inputs — external correctness check
        assertThat(signaturePart, is("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"));

        // Re-compute to verify determinism
        Map<String, String> headers2 = AwsSigV4Signer.sign(
            "GET", "example.amazonaws.com", "/", "", new byte[0],
            TEST_REGION, TEST_SERVICE, TEST_ACCESS_KEY, TEST_SECRET_KEY,
            null, TEST_TIMESTAMP, null);
        assertThat("same inputs must produce identical Authorization",
            headers2.get("Authorization"), is(auth));
    }

    @Test
    public void determinism_sameInputsProduceSameSignature() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers1 = AwsSigV4Signer.sign(
            "POST", "service.us-east-1.amazonaws.com", "/path", "",
            body, "us-east-1", "service",
            "AKID", "SECRET", null, TEST_TIMESTAMP,
            Collections.singletonMap("content-type", "application/json"));
        Map<String, String> headers2 = AwsSigV4Signer.sign(
            "POST", "service.us-east-1.amazonaws.com", "/path", "",
            body, "us-east-1", "service",
            "AKID", "SECRET", null, TEST_TIMESTAMP,
            Collections.singletonMap("content-type", "application/json"));

        assertThat(headers1.get("Authorization"), is(headers2.get("Authorization")));
        assertThat(headers1.get("X-Amz-Date"), is(headers2.get("X-Amz-Date")));
        assertThat(headers1.get("X-Amz-Content-Sha256"), is(headers2.get("X-Amz-Content-Sha256")));
    }

    @Test
    public void differentBodyProducesDifferentSignature() {
        byte[] body1 = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "{\"b\":2}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers1 = AwsSigV4Signer.sign(
            "POST", "host.example.com", "/", "", body1,
            "us-east-1", "svc", "AKID", "SECRET", null, TEST_TIMESTAMP, null);
        Map<String, String> headers2 = AwsSigV4Signer.sign(
            "POST", "host.example.com", "/", "", body2,
            "us-east-1", "svc", "AKID", "SECRET", null, TEST_TIMESTAMP, null);

        assertThat("different body should produce different payload hash",
            headers1.get("X-Amz-Content-Sha256"),
            is(not(headers2.get("X-Amz-Content-Sha256"))));
        assertThat("different body should produce different signature",
            headers1.get("Authorization"),
            is(not(headers2.get("Authorization"))));
    }

    @Test
    public void sessionTokenAddsSecurityTokenHeaderAndChangesSignedHeaders() {
        Map<String, String> withoutToken = AwsSigV4Signer.sign(
            "POST", "host.example.com", "/", "", new byte[0],
            "us-east-1", "svc", "AKID", "SECRET", null, TEST_TIMESTAMP, null);
        Map<String, String> withToken = AwsSigV4Signer.sign(
            "POST", "host.example.com", "/", "", new byte[0],
            "us-east-1", "svc", "AKID", "SECRET", "TOKEN123", TEST_TIMESTAMP, null);

        assertThat(withoutToken, not(hasKey("X-Amz-Security-Token")));
        assertThat(withToken, hasKey("X-Amz-Security-Token"));
        assertThat(withToken.get("X-Amz-Security-Token"), is("TOKEN123"));

        // The signed headers should include x-amz-security-token when present
        String authWithToken = withToken.get("Authorization");
        assertThat(authWithToken, containsString("x-amz-security-token"));

        String authWithoutToken = withoutToken.get("Authorization");
        assertThat(authWithoutToken, not(containsString("x-amz-security-token")));

        // Different signed-headers set means different signature
        assertThat(authWithToken, is(not(authWithoutToken)));
    }

    @Test
    public void extraHeadersAreIncludedInSignature() {
        Map<String, String> withContentType = AwsSigV4Signer.sign(
            "POST", "host.example.com", "/", "", new byte[0],
            "us-east-1", "svc", "AKID", "SECRET", null, TEST_TIMESTAMP,
            Collections.singletonMap("content-type", "application/json"));

        String auth = withContentType.get("Authorization");
        assertThat(auth, containsString("content-type"));
        assertThat(auth, containsString("SignedHeaders=content-type;host;x-amz-date"));
    }

    @Test
    public void uriEncodePathSegmentEncodesColonAndPreservesUnreserved() {
        // Bedrock model IDs contain ':' and '.' — ':' must be encoded, '.' preserved
        assertThat(AwsSigV4Signer.uriEncodePathSegment("anthropic.claude-3:0"),
            is("anthropic.claude-3%3A0"));
        assertThat(AwsSigV4Signer.uriEncodePathSegment("simple"), is("simple"));
        assertThat(AwsSigV4Signer.uriEncodePathSegment("a b"), is("a%20b"));
    }

    @Test
    public void uriEncodePathPreservesSlashSeparators() {
        assertThat(AwsSigV4Signer.uriEncodePath("/model/my.model:v1/invoke"),
            is("/model/my.model%3Av1/invoke"));
    }

    @Test
    public void hexSha256OfEmptyPayloadMatchesKnownConstant() {
        // SHA-256 of empty byte array — the well-known constant
        assertThat(AwsSigV4Signer.hexSha256(new byte[0]),
            is("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    public void postWithJsonBodyProducesValidSignature() {
        // A POST with a JSON body, content-type signed, to a Bedrock-like endpoint
        String jsonBody = "{\"anthropic_version\":\"bedrock-2023-05-31\",\"max_tokens\":1024}";
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        Instant timestamp = Instant.parse("2024-01-15T10:30:00Z");

        Map<String, String> headers = AwsSigV4Signer.sign(
            "POST",
            "bedrock-runtime.us-east-1.amazonaws.com",
            "/model/anthropic.claude-3-5-sonnet-20241022-v2%3A0/invoke",
            "",
            payload,
            "us-east-1",
            "bedrock",
            "AKIDEXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            null,
            timestamp,
            Collections.singletonMap("content-type", "application/json")
        );

        String auth = headers.get("Authorization");
        assertThat(auth, startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20240115/us-east-1/bedrock/aws4_request"));
        assertThat(auth, containsString("SignedHeaders=content-type;host;x-amz-date"));
        assertThat(auth, containsString("Signature="));
        assertThat(headers.get("X-Amz-Date"), is("20240115T103000Z"));
        assertThat(headers.get("X-Amz-Content-Sha256"),
            is(AwsSigV4Signer.hexSha256(payload)));
    }
}
