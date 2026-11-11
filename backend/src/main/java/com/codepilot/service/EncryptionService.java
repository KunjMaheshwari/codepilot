package com.codepilot.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final String CURRENT_FORMAT = "v2";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${codepilot.encryption.key}")
    private String encryptionKeyHex;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = decodeKey(encryptionKeyHex);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] encryptedData = new byte[nonce.length + cipherText.length];
            System.arraycopy(nonce, 0, encryptedData, 0, nonce.length);
            System.arraycopy(cipherText, 0, encryptedData, nonce.length, cipherText.length);

            return CURRENT_FORMAT + ":" + Base64.getEncoder().encodeToString(encryptedData);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt data", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        try {
            boolean versioned = encryptedText.startsWith(CURRENT_FORMAT + ":");
            String encodedData = versioned
                    ? encryptedText.substring((CURRENT_FORMAT + ":").length())
                    : encryptedText;
            byte[] encryptedData = Base64.getDecoder().decode(encodedData);

            if (encryptedData.length < NONCE_LENGTH_BYTES + (TAG_LENGTH_BIT / Byte.SIZE)) {
                throw new IllegalArgumentException("Invalid encrypted data format");
            }

            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            System.arraycopy(encryptedData, 0, nonce, 0, nonce.length);

            byte[] cipherText = new byte[encryptedData.length - NONCE_LENGTH_BYTES];
            System.arraycopy(encryptedData, NONCE_LENGTH_BYTES, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, nonce);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt data", e);
        }
    }

    private byte[] decodeKey(String hexString) {
        if (hexString == null || hexString.length() != 64) {
            throw new IllegalStateException("Encryption key must be a 64-character hex string (256-bit key).");
        }
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hexString.charAt(i), 16);
            int low = Character.digit(hexString.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalStateException("Encryption key must be a 64-character hex string (256-bit key).");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }
}
