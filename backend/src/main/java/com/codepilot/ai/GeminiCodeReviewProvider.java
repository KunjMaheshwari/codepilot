package com.codepilot.ai;

import com.codepilot.exception.GeminiApiException;
import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class GeminiCodeReviewProvider implements CodeReviewProvider {

    private static final long MAX_CONNECT_TIMEOUT_MS = 30_000L;
    private static final long MAX_READ_TIMEOUT_MS = 120_000L;

    private static final Map<String, Object> FINDING_RESPONSE_SCHEMA = Map.of(
            "type", "ARRAY",
            "items", Map.of(
                    "type", "OBJECT",
                    "required", List.of(
                            "title", "description", "category", "severity",
                            "filePath", "lineNumber", "evidence", "impact",
                            "suggestedFix", "confidence"),
                    "properties", Map.ofEntries(
                            Map.entry("title", Map.of("type", "STRING")),
                            Map.entry("description", Map.of("type", "STRING")),
                            Map.entry("category", Map.of(
                                    "type", "STRING",
                                    "enum", List.of("BUG", "SECURITY", "PERFORMANCE",
                                            "RELIABILITY", "MAINTAINABILITY", "CODE_QUALITY"))),
                            Map.entry("severity", Map.of(
                                    "type", "STRING",
                                    "enum", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"))),
                            Map.entry("filePath", Map.of("type", "STRING")),
                            Map.entry("lineNumber", Map.of("type", "INTEGER")),
                            Map.entry("evidence", Map.of("type", "STRING")),
                            Map.entry("impact", Map.of("type", "STRING")),
                            Map.entry("suggestedFix", Map.of("type", "STRING")),
                            Map.entry("confidence", Map.of(
                                    "type", "STRING",
                                    "enum", List.of("HIGH", "MEDIUM", "LOW"))))));

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewResponseValidator responseValidator;
    private final ReviewPromptBuilder reviewPromptBuilder;

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong findingsGeneratedCount = new AtomicLong();

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    @Value("${gemini.api.model:gemini-3-flash-preview}")
    private String model;

    @Autowired
    public GeminiCodeReviewProvider(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            ReviewResponseValidator responseValidator,
            ReviewPromptBuilder reviewPromptBuilder,
            @Value("${gemini.api.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${gemini.api.read-timeout-ms:45000}") long readTimeoutMs) {
        long boundedConnectMs = Math.max(1000L, Math.min(connectTimeoutMs, MAX_CONNECT_TIMEOUT_MS));
        long boundedReadMs = Math.max(1000L, Math.min(readTimeoutMs, MAX_READ_TIMEOUT_MS));
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(boundedConnectMs))
                .setReadTimeout(Duration.ofMillis(boundedReadMs))
                .build();
        this.objectMapper = objectMapper;
        this.responseValidator = responseValidator;
        this.reviewPromptBuilder = reviewPromptBuilder;
    }

    public GeminiCodeReviewProvider(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            ReviewResponseValidator responseValidator,
            ReviewPromptBuilder reviewPromptBuilder) {
        this(restTemplateBuilder, objectMapper, responseValidator, reviewPromptBuilder, 5000L, 45000L);
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    public long getRetryCount() {
        return retryCount.get();
    }

    public long getFindingsGeneratedCount() {
        return findingsGeneratedCount.get();
    }

    @Override
    public List<ReviewFinding> performReview(String prompt) {
        throw new IllegalArgumentException("Review diff context is required for finding validation");
    }

    @Override
    public String performRawReview(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Review prompt is empty");
        }
        return invokeGemini(null, prompt);
    }

    @Override
    public List<ReviewFinding> reviewDiff(String diff, List<DiffParser.ParsedFile> parsedFiles) {
        if (parsedFiles == null || parsedFiles.isEmpty()) {
            return List.of();
        }
        String systemInstruction = reviewPromptBuilder.buildSystemInstruction();
        String untrustedContent = reviewPromptBuilder.buildUntrustedDiffContent(parsedFiles);
        String rawResponse = invokeGemini(systemInstruction, untrustedContent);
        List<ReviewFinding> findings = responseValidator.validateAndParse(rawResponse, parsedFiles);
        findingsGeneratedCount.addAndGet(findings.size());
        log.info("gemini_findings_generated count={}", findings.size());
        return findings;
    }

    private String invokeGemini(String systemInstruction, String userContent) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GeminiApiException(
                    "Gemini API key is not configured",
                    HttpStatus.UNAUTHORIZED,
                    "GEMINI_API_ERROR",
                    false);
        }

        String baseUrl = normalizeBaseUrl(apiUrl);
        String resolvedModel = normalizeModel(model);
        String endpoint = baseUrl + "/v1beta/models/" + resolvedModel + ":generateContent";
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.1);
        generationConfig.put("responseSchema", FINDING_RESPONSE_SCHEMA);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            requestBody.put("systemInstruction",
                    Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        requestBody.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userContent)))));
        requestBody.put("generationConfig", generationConfig);

        long startNanos = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint, new HttpEntity<>(requestBody, headers), String.class);
            String text = extractText(response.getBody());
            successCount.incrementAndGet();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info("gemini_request_success model={} latencyMs={}", resolvedModel, latencyMs);
            return text;
        } catch (HttpStatusCodeException exception) {
            failureCount.incrementAndGet();
            int statusCode = exception.getStatusCode().value();
            HttpStatus status = HttpStatus.resolve(statusCode);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String safeError = extractSafeErrorDetail(exception.getResponseBodyAsString());
            Long retryAfterSeconds = parseRetryAfterSeconds(exception.getResponseHeaders());
            boolean retryable = statusCode == 429 || statusCode >= 500;
            String category = retryable ? "GEMINI_API_TRANSIENT" : "GEMINI_API_ERROR";
            if (retryable) {
                retryCount.incrementAndGet();
            }
            log.warn("gemini_request_failed status={} model={} retryable={} latencyMs={} reason={}",
                    statusCode, resolvedModel, retryable, latencyMs, safeError != null ? safeError : "http_error");
            String detailSuffix = safeError != null ? ": " + safeError : "";
            throw new GeminiApiException(
                    "Gemini review request failed with status " + statusCode + " for model '" + resolvedModel + "'" + detailSuffix,
                    status != null ? status : HttpStatus.BAD_GATEWAY,
                    category,
                    retryable,
                    retryAfterSeconds,
                    exception);
        } catch (ResourceAccessException exception) {
            failureCount.incrementAndGet();
            retryCount.incrementAndGet();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("gemini_request_failed reason=timeout_or_io model={} latencyMs={}", resolvedModel, latencyMs);
            throw new GeminiApiException(
                    "Gemini review request failed due to timeout or network error",
                    HttpStatus.GATEWAY_TIMEOUT,
                    "GEMINI_API_TRANSIENT",
                    true,
                    null,
                    exception);
        } catch (GeminiApiException exception) {
            failureCount.incrementAndGet();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("gemini_request_failed reason=invalid_response model={} latencyMs={}", resolvedModel, latencyMs);
            throw exception;
        }
    }

    private String extractText(String body) {
        if (body == null || body.isBlank()) {
            throw new GeminiApiException(
                    "Gemini response body was empty",
                    HttpStatus.BAD_GATEWAY,
                    "INVALID_AI_RESPONSE",
                    true);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode blockReason = root.path("promptFeedback").path("blockReason");
            if (blockReason.isTextual() && !blockReason.asText().isBlank()) {
                throw new GeminiApiException(
                        "Gemini blocked the review request: " + blockReason.asText(),
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "GEMINI_API_ERROR",
                        false);
            }
            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isTextual() || text.asText().isBlank()) {
                throw new GeminiApiException(
                        "Gemini response did not contain structured review content",
                        HttpStatus.BAD_GATEWAY,
                        "INVALID_AI_RESPONSE",
                        true);
            }
            return text.asText();
        } catch (GeminiApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GeminiApiException(
                    "Gemini response was malformed",
                    HttpStatus.BAD_GATEWAY,
                    "INVALID_AI_RESPONSE",
                    true,
                    null,
                    exception);
        }
    }

    private Long parseRetryAfterSeconds(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        try {
            return Math.max(0L, Long.parseLong(retryAfter.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "https://generativelanguage.googleapis.com";
        }
        String trimmed = rawUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String normalizeModel(String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return "gemini-3-flash-preview";
        }
        String trimmed = rawModel.trim();
        return trimmed.startsWith("models/") ? trimmed.substring("models/".length()) : trimmed;
    }

    private String extractSafeErrorDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            String msg = errorNode.path("message").asText("").trim();
            String errStatus = errorNode.path("status").asText("").trim();
            if (msg.isBlank()) {
                return null;
            }
            String combined = errStatus.isBlank() ? msg : errStatus + " - " + msg;
            if (apiKey != null && !apiKey.isBlank()) {
                combined = combined.replace(apiKey, "[REDACTED]");
            }
            combined = combined.replaceAll("([?&]key=)[^&\\s]+", "$1[REDACTED]");
            return combined.length() > 240 ? combined.substring(0, 240) : combined;
        } catch (Exception ignored) {
            return null;
        }
    }
}
