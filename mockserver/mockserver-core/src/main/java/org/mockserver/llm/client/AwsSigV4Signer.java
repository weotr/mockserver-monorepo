package org.mockserver.llm.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Stateless, deterministic implementation of AWS Signature Version 4 (header-based
 * {@code Authorization} header signing) using only JDK crypto primitives:
 * {@link MessageDigest} (SHA-256) and {@link Mac} / {@link SecretKeySpec}
 * (HmacSHA256). No third-party dependencies.
 * <p>
 * The implementation follows the canonical algorithm documented at
 * <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html">
 * AWS Signature Version 4 Signing Process</a> and is verifiable offline against
 * AWS's published SigV4 test suite vectors.
 */
public final class AwsSigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";

    static final DateTimeFormatter AMZ_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter DATE_STAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4Signer() {
        // utility class
    }

    /**
     * Compute the AWS SigV4 headers for a request. Returns a map of headers to
     * ADD to the request: {@code Authorization}, {@code X-Amz-Date},
     * {@code X-Amz-Content-Sha256}, and (when {@code sessionToken} is non-null)
     * {@code X-Amz-Security-Token}.
     *
     * @param method               HTTP method (e.g. "POST")
     * @param host                 host header value (e.g. "bedrock-runtime.us-east-1.amazonaws.com")
     * @param canonicalUri         the URI path, with each segment percent-encoded per SigV4 rules
     * @param canonicalQueryString the query string (already sorted; empty string if none)
     * @param payload              the request body bytes (the real body, not UNSIGNED-PAYLOAD)
     * @param region               AWS region (e.g. "us-east-1")
     * @param service              AWS service (e.g. "bedrock")
     * @param accessKeyId          AWS access key ID
     * @param secretAccessKey      AWS secret access key
     * @param sessionToken         AWS session token (nullable; from STS assume-role)
     * @param timestamp            the signing timestamp (deterministic input for testability)
     * @param extraHeadersToSign   additional headers to include in the signature (e.g. content-type);
     *                             nullable. Values must match what will be sent on the wire.
     * @return unmodifiable map of headers to add to the request
     */
    public static Map<String, String> sign(
        String method,
        String host,
        String canonicalUri,
        String canonicalQueryString,
        byte[] payload,
        String region,
        String service,
        String accessKeyId,
        String secretAccessKey,
        String sessionToken,
        Instant timestamp,
        Map<String, String> extraHeadersToSign
    ) {
        String amzDate = AMZ_DATE_FORMAT.format(timestamp);
        String dateStamp = DATE_STAMP_FORMAT.format(timestamp);
        String payloadHash = hexSha256(payload);

        // Build the set of headers to sign: host, x-amz-date, payload hash,
        // optional security token, plus any extra headers
        TreeMap<String, String> headersToSign = new TreeMap<>();
        headersToSign.put("host", host);
        headersToSign.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isEmpty()) {
            headersToSign.put("x-amz-security-token", sessionToken);
        }
        if (extraHeadersToSign != null) {
            for (Map.Entry<String, String> entry : extraHeadersToSign.entrySet()) {
                headersToSign.put(entry.getKey().toLowerCase(Locale.US), trimAll(entry.getValue()));
            }
        }

        // Step 1: Canonical request
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeadersBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : headersToSign.entrySet()) {
            canonicalHeaders.append(entry.getKey()).append(':').append(trimAll(entry.getValue())).append('\n');
            if (signedHeadersBuilder.length() > 0) {
                signedHeadersBuilder.append(';');
            }
            signedHeadersBuilder.append(entry.getKey());
        }
        String signedHeaders = signedHeadersBuilder.toString();

        String canonicalRequest = method + "\n"
            + canonicalUri + "\n"
            + canonicalQueryString + "\n"
            + canonicalHeaders + "\n"
            + signedHeaders + "\n"
            + payloadHash;

        // Step 2: String to sign
        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
            + amzDate + "\n"
            + credentialScope + "\n"
            + hexSha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // Step 3: Signing key
        byte[] signingKey = deriveSigningKey(secretAccessKey, dateStamp, region, service);

        // Step 4: Signature
        String signature = hexEncode(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        // Step 5: Authorization header
        String authorization = ALGORITHM + " "
            + "Credential=" + accessKeyId + "/" + credentialScope + ", "
            + "SignedHeaders=" + signedHeaders + ", "
            + "Signature=" + signature;

        // Build result headers
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("Authorization", authorization);
        result.put("X-Amz-Date", amzDate);
        result.put("X-Amz-Content-Sha256", payloadHash);
        if (sessionToken != null && !sessionToken.isEmpty()) {
            result.put("X-Amz-Security-Token", sessionToken);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Derive the SigV4 signing key. Exposed at package scope for test-vector
     * verification (the AWS docs publish expected signing-key hex values).
     */
    static byte[] deriveSigningKey(String secretAccessKey, String dateStamp, String region, String service) {
        byte[] kSecret = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * URI-encode a path segment per SigV4 rules: all characters except unreserved
     * (A-Z, a-z, 0-9, '-', '.', '_', '~') are percent-encoded. Unlike standard
     * URL encoding, '/' is NOT encoded when encoding a full path (but IS encoded
     * within a segment). Callers encode each segment separately and join with '/'.
     */
    static String uriEncodePathSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length() * 2);
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (b & 0xFF);
            if (isUnreserved(ch)) {
                encoded.append(ch);
            } else {
                encoded.append('%');
                encoded.append(HEX_CHARS_UPPER[(b >> 4) & 0x0F]);
                encoded.append(HEX_CHARS_UPPER[b & 0x0F]);
            }
        }
        return encoded.toString();
    }

    /**
     * URI-encode a full path (preserving '/' separators) per SigV4 rules.
     * Each segment between '/' is individually percent-encoded.
     */
    static String uriEncodePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String[] segments = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(uriEncodePathSegment(segments[i]));
        }
        return encoded.toString();
    }

    // --- internal helpers ---

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    /** Uppercase hex for URI percent-encoding (SigV4 requires uppercase %XX). */
    private static final char[] HEX_CHARS_UPPER = "0123456789ABCDEF".toCharArray();

    private static boolean isUnreserved(char ch) {
        return (ch >= 'A' && ch <= 'Z')
            || (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9')
            || ch == '-' || ch == '.' || ch == '_' || ch == '~';
    }

    static String hexSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            return hexEncode(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String hexEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(HEX_CHARS[(b >> 4) & 0x0F]);
            sb.append(HEX_CHARS[b & 0x0F]);
        }
        return sb.toString();
    }

    static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 computation failed", e);
        }
    }

    /**
     * Trim leading/trailing whitespace and collapse internal runs of whitespace
     * to a single space, as required for canonical header values.
     */
    private static String trimAll(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
