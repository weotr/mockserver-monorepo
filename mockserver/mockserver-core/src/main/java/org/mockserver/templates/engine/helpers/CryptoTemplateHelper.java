package org.mockserver.templates.engine.helpers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Cryptographic hashing and HMAC helpers for templates. All digests are returned
 * as lowercase hexadecimal strings. Base64 encoding/decoding is provided by
 * {@link StringTemplateHelper} and is intentionally not duplicated here.
 */
public class CryptoTemplateHelper {

    public String md5(String value) {
        return digest("MD5", value);
    }

    public String sha1(String value) {
        return digest("SHA-1", value);
    }

    public String sha256(String value) {
        return digest("SHA-256", value);
    }

    public String sha512(String value) {
        return digest("SHA-512", value);
    }

    public String hmacSha256(String key, String data) {
        if (key == null || data == null) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new RuntimeException("Exception computing HmacSHA256", exception);
        }
    }

    private String digest(String algorithm, String value) {
        if (value == null) {
            return "";
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
            return toHex(messageDigest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException("Exception computing " + algorithm + " digest", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(Character.forDigit((b >> 4) & 0xF, 16));
            result.append(Character.forDigit(b & 0xF, 16));
        }
        return result.toString();
    }

    @Override
    public String toString() {
        return "CryptoTemplateHelper";
    }
}
