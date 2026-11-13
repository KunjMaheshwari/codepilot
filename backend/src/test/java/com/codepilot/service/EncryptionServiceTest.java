package com.codepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private EncryptionService encryptionService;
    private static final String VALID_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService();
    }

    @Test
    void shouldEncryptAndDecryptSuccessfully() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", VALID_KEY);
        encryptionService.init();

        String originalText = "gho_secretAccessToken123";
        String encrypted = encryptionService.encrypt(originalText);

        assertThat(encrypted).isNotEqualTo(originalText);

        String decrypted = encryptionService.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(originalText);
    }

    @Test
    void shouldProduceDifferentCiphertextsForSameInput() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", VALID_KEY);
        encryptionService.init();

        String originalText = "gho_secretAccessToken123";
        String encrypted1 = encryptionService.encrypt(originalText);
        String encrypted2 = encryptionService.encrypt(originalText);

        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    void shouldDecryptLegacyUnversionedCiphertext() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", VALID_KEY);
        encryptionService.init();

        String versionedCiphertext = encryptionService.encrypt("legacy-token");
        String legacyCiphertext = versionedCiphertext.substring("v2:".length());

        assertThat(encryptionService.decrypt(legacyCiphertext)).isEqualTo("legacy-token");
    }

    @Test
    void shouldRejectMissingEncryptionKey() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", null);

        assertThatThrownBy(() -> encryptionService.init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Encryption key must be a 64-character hex string");
    }

    @Test
    void shouldThrowExceptionForInvalidKeyLength() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", "invalid_length");

        assertThatThrownBy(() -> encryptionService.init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Encryption key must be a 64-character hex string");
    }

    @Test
    void shouldThrowExceptionForCorruptedCiphertext() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", VALID_KEY);
        encryptionService.init();

        assertThatThrownBy(() -> encryptionService.decrypt("corruptedBase64=="))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to decrypt data");
    }

    @Test
    void shouldRejectTamperedCiphertext() {
        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", VALID_KEY);
        encryptionService.init();

        String encrypted = encryptionService.encrypt("token");
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> encryptionService.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decrypt data");
    }

    @Test
    void shouldDecryptCiphertextEncryptedWithPreviousRotationKey() {
        String oldKey = VALID_KEY;
        String newKey = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

        EncryptionService oldService = new EncryptionService();
        ReflectionTestUtils.setField(oldService, "encryptionKeyHex", oldKey);
        oldService.init();
        String encryptedWithOld = oldService.encrypt("gho_rotatedToken");

        ReflectionTestUtils.setField(encryptionService, "encryptionKeyHex", newKey);
        ReflectionTestUtils.setField(encryptionService, "previousKeysCsv", oldKey);
        encryptionService.init();

        assertThat(encryptionService.decrypt(encryptedWithOld)).isEqualTo("gho_rotatedToken");
    }
}
