package com.codepilot.config;

import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.GitHubIntegrationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigTest {

    @Test
    void corsConfigurationNormalizesOriginsAndExcludesWildcards() {
        SecurityConfig config = new SecurityConfig(
                mock(UserRepository.class),
                mock(ConnectedGitHubAccountRepository.class),
                mock(GitHubIntegrationService.class),
                new RateLimitFilter());
        ReflectionTestUtils.setField(config, "allowedOrigins",
                List.of("https://codepilot.example.com/", " ", "*", "http://localhost:3000/"));

        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/repositories"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins())
                .containsExactly("https://codepilot.example.com", "http://localhost:3000")
                .doesNotContain("*");
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getExposedHeaders()).contains("X-XSRF-TOKEN");
        assertThat(cors.getAllowedHeaders()).contains("X-XSRF-TOKEN", "Content-Type");
    }
}
