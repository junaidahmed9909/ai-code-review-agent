package com.junaid.review.core;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies GitHub's X-Hub-Signature-256 header: "sha256=" + hex(HMAC-SHA256(secret, raw body)).
 * Uses a constant-time comparison so the check can't leak timing information.
 */
public final class WebhookSignature {

    private static final String PREFIX = "sha256=";

    private WebhookSignature() {}

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    public static boolean isValid(String secret, byte[] body, String signatureHeader) {
        if (secret == null || secret.isEmpty() || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signatureHeader.trim().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
