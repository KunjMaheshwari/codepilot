package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiCodeReviewProviderTest {

    private GeminiCodeReviewProvider provider;
    private MockRestServiceServer mockServer;
    private DiffParser.ParsedFile sampleFile;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        provider = new GeminiCodeReviewProvider(
                new RestTemplateBuilder(),
                mapper,
                new ReviewResponseValidator(mapper),
                new ReviewPromptBuilder());
        ReflectionTestUtils.setField(provider, "apiKey", "test-gemini-key");
        ReflectionTestUtils.setField(provider, "apiUrl", "https://gemini.test");
        ReflectionTestUtils.setField(provider, "model", "gemini-2.5-flash");

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        mockServer = MockRestServiceServer.createServer(restTemplate);

        sampleFile = new DiffParser.ParsedFile();
        sampleFile.setFilePath("src/App.java");
        sampleFile.getAddedLines().add(10);
        sampleFile.setDiffContent(new StringBuilder("+ String sql = \"SELECT * FROM users WHERE id=\" + id;"));
    }

    @Test
    void sendsSystemInstructionAndResponseSchemaAndTracksSuccessMetrics() {
        String responseJson = """
                {"candidates":[{"content":{"parts":[{"text":"[{\\"title\\":\\"SQL Injection\\",\\"description\\":\\"Concatenated SQL query\\",\\"category\\":\\"SECURITY\\",\\"severity\\":\\"CRITICAL\\",\\"filePath\\":\\"src/App.java\\",\\"lineNumber\\":10,\\"evidence\\":\\"SELECT * FROM users\\",\\"impact\\":\\"Database compromise\\",\\"suggestedFix\\":\\"Use PreparedStatement\\",\\"confidence\\":\\"HIGH\\"}]"}]}}]}
                """;
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-gemini-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"systemInstruction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"responseSchema\"")))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<ReviewFinding> findings = provider.reviewDiff("diff", List.of(sampleFile));

        mockServer.verify();
        assertThat(findings).hasSize(1);
        assertThat(provider.getSuccessCount()).isEqualTo(1);
        assertThat(provider.getFindingsGeneratedCount()).isEqualTo(1);
    }

    @Test
    void doesNotRetryPermanent401Error() {
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
        mockServer.verify();
        assertThat(provider.getFailureCount()).isEqualTo(1);
        assertThat(provider.getRetryCount()).isZero();
    }

    @Test
    void retriesTimeoutAndExhaustsAfterMaxAttempts() {
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                    .andRespond(request -> {
                        throw new ResourceAccessException("Read timed out");
                    });
        }

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout");
        mockServer.verify();
        assertThat(provider.getRetryCount()).isEqualTo(2);
        assertThat(provider.getFailureCount()).isEqualTo(1);
    }

    @Test
    void rejectsSafetyBlockedAndMalformedGeminiPayloads() {
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY");
        mockServer.verify();
    }
}

