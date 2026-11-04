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
}
