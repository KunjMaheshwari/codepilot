package com.codepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookSignatureVerifierTest {

    private static final String SECRET = "webhook-secret";
    private final GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier();
    private final byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(verifier, "secret", SECRET);
    }

    @Test
    void acceptsValidSignature() throws Exception {
        assertThat(verifier.isValid(payload, signature(payload))).isTrue();
    }

    @Test
    void rejectsMissingSignature() {
        assertThat(verifier.isValid(payload, null)).isFalse();
    }

    @Test
    void rejectsMalformedSignature() {
        assertThat(verifier.isValid(payload, "sha256=not-hex")).isFalse();
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        assertThat(verifier.isValid(
                "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8), signature(payload))).isFalse();
    }

    @Test
    void rejectsWrongSecretSignature() throws Exception {
        assertThat(verifier.isValid(payload, "sha256=" + "0".repeat(64))).isFalse();
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte value : mac.doFinal(body)) {
            hex.append(String.format("%02x", value));
        }
        return "sha256=" + hex;
    }
}
