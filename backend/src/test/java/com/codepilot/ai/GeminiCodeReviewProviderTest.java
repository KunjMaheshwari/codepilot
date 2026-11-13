package com.codepilot.ai;

import com.codepilot.exception.GeminiApiException;
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
    void classifiesTimeoutAsTransientWithoutBlockingWorkerThread() {
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out");
                });

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("timeout")
                .satisfies(ex -> {
                    GeminiApiException gex = (GeminiApiException) ex;
                    assertThat(gex.isRetryable()).isTrue();
                    assertThat(gex.getErrorCategory()).isEqualTo("GEMINI_API_TRANSIENT");
                });
        mockServer.verify();
        assertThat(provider.getRetryCount()).isEqualTo(1);
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

    @Test
    void supportsGemini38FlashAndNormalizesModelsPrefixAndTrailingSlash() {
        ReflectionTestUtils.setField(provider, "apiUrl", "https://gemini.test/");
        ReflectionTestUtils.setField(provider, "model", "models/gemini-3.8-flash");

        String responseJson = """
                {"candidates":[{"content":{"parts":[{"text":"[]"}]}}]}
                """;
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-3.8-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<ReviewFinding> findings = provider.reviewDiff("diff", List.of(sampleFile));

        mockServer.verify();
        assertThat(findings).isEmpty();
        assertThat(provider.getSuccessCount()).isEqualTo(1);
    }

    @Test
    void exposesSafeErrorMessageAndModelOn404WithoutLeakingApiKey() {
        ReflectionTestUtils.setField(provider, "model", "gemini-nonexistent");
        String errorBody = """
                {"error":{"code":404,"message":"models/gemini-nonexistent is not found for API version v1beta (key=test-gemini-key)","status":"NOT_FOUND"}}
                """;
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-nonexistent:generateContent"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorBody));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("gemini-nonexistent")
                .hasMessageContaining("NOT_FOUND")
                .hasMessageNotContaining("test-gemini-key");
        mockServer.verify();
        assertThat(provider.getRetryCount()).isZero();
    }

    @Test
    void doesNotRetryPermanent403AuthenticationError() {
        String errorBody = """
                {"error":{"code":403,"message":"API key not valid.","status":"PERMISSION_DENIED"}}
                """;
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorBody));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("PERMISSION_DENIED");
        mockServer.verify();
        assertThat(provider.getRetryCount()).isZero();
    }

    @Test
    void classifiesQuota429AsRetryableAndCapturesRetryAfterHeader() {
        String quotaBody = """
                {"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}
                """;
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Retry-After", "15");
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .headers(headers)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(quotaBody));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("RESOURCE_EXHAUSTED")
                .satisfies(ex -> {
                    GeminiApiException gex = (GeminiApiException) ex;
                    assertThat(gex.isRetryable()).isTrue();
                    assertThat(gex.getRetryAfterSeconds()).isEqualTo(15L);
                    assertThat(gex.getErrorCategory()).isEqualTo("GEMINI_API_TRANSIENT");
                });
        mockServer.verify();
        assertThat(provider.getRetryCount()).isEqualTo(1);
        assertThat(provider.getFailureCount()).isEqualTo(1);
    }

    @Test
    void classifies503AsTransientServerFailure() {
        String unavailableBody = """
                {"error":{"code":503,"message":"High demand","status":"UNAVAILABLE"}}
                """;
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(unavailableBody));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("503")
                .satisfies(ex -> {
                    GeminiApiException gex = (GeminiApiException) ex;
                    assertThat(gex.isRetryable()).isTrue();
                    assertThat(gex.getErrorCategory()).isEqualTo("GEMINI_API_TRANSIENT");
                });
        mockServer.verify();
    }

    @Test
    void rejectsMalformedOrEmptyGeminiResponses() {
        mockServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.reviewDiff("diff", List.of(sampleFile)))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("malformed")
                .satisfies(ex -> assertThat(((GeminiApiException) ex).getErrorCategory()).isEqualTo("INVALID_AI_RESPONSE"));
        mockServer.verify();
    }
}

