package com.codepilot.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StartupConfigurationValidator {

    private final Environment environment;

    @PostConstruct
    void validateRequiredProperties() {
        boolean isTestProfile = Arrays.asList(environment.getActiveProfiles()).contains("test");
        if (isTestProfile) {
            return;
        }

        List<String> missing = new ArrayList<>();
        requireNonBlank("spring.datasource.url", "DB_URL", missing);
        requireNonBlank("spring.datasource.username", "DB_USERNAME", missing);
        requireNonBlank("codepilot.encryption.key", "ENCRYPTION_KEY", missing);
        requireNonBlank("gemini.api.key", "GEMINI_API_KEY", missing);
        requireNonBlank("spring.security.oauth2.client.registration.github.client-id", "GITHUB_CLIENT_ID", missing);
        requireNonBlank("spring.security.oauth2.client.registration.github.client-secret", "GITHUB_CLIENT_SECRET", missing);
        requireNonBlank("github.webhook.secret", "GITHUB_WEBHOOK_SECRET", missing);

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required configuration environment variables: " + String.join(", ", missing));
        }
    }

    private void requireNonBlank(String property, String envName, List<String> missing) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            missing.add(envName);
        }
    }
}

