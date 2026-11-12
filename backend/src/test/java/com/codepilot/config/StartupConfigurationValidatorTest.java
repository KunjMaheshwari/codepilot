package com.codepilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupConfigurationValidatorTest {

    @Test
    void skipsValidationInTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        StartupConfigurationValidator validator = new StartupConfigurationValidator(env);
        assertThatCode(validator::validateRequiredProperties).doesNotThrowAnyException();
    }

    @Test
    void failsWithClearMessageWhenMandatoryPropertiesAreMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        StartupConfigurationValidator validator = new StartupConfigurationValidator(env);
        assertThatThrownBy(validator::validateRequiredProperties)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL")
                .hasMessageContaining("ENCRYPTION_KEY")
                .hasMessageContaining("GEMINI_API_KEY")
                .hasMessageContaining("GITHUB_WEBHOOK_SECRET");
    }

    @Test
    void passesWhenAllMandatoryPropertiesArePresent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/codepilot")
                .withProperty("spring.datasource.username", "codepilot")
                .withProperty("codepilot.encryption.key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .withProperty("gemini.api.key", "test-gemini-key")
                .withProperty("spring.security.oauth2.client.registration.github.client-id", "cid")
                .withProperty("spring.security.oauth2.client.registration.github.client-secret", "csec")
                .withProperty("github.webhook.secret", "wsec");

        StartupConfigurationValidator validator = new StartupConfigurationValidator(env);
        assertThatCode(validator::validateRequiredProperties).doesNotThrowAnyException();
    }
}

