package com.codepilot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Component
public class GitHubWebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    @Value("${github.webhook.secret:}")
    private String secret;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @PostConstruct
    void validateConfiguration() {
        if ((activeProfiles == null || !activeProfiles.contains("test"))
                && (secret == null || secret.isBlank())) {
            throw new IllegalStateException("GITHUB_WEBHOOK_SECRET must be configured outside the test profile");
        }
    }

    public boolean isValid(byte[] payload, String signature) {
        if (payload == null || signature == null || !signature.startsWith(PREFIX)
                || signature.length() != PREFIX.length() + 64 || secret == null || secret.isBlank()) {
            return false;
        }
        try {
            byte[] expected = hmac(payload, secret);
            byte[] provided = hexDecode(signature.substring(PREFIX.length()));
            return java.security.MessageDigest.isEqual(expected, provided);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] hmac(byte[] payload, String signingSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Webhook signature verification is unavailable", exception);
        }
    }

    private byte[] hexDecode(String value) {
        byte[] decoded = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Malformed webhook signature");
            }
            decoded[i / 2] = (byte) ((high << 4) + low);
        }
        return decoded;
    }
}
