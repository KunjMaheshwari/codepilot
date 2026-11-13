package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class GeminiCodeReviewProvider implements CodeReviewProvider {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_RETRY_DELAY_MS = 250L;
    private static final long MAX_RETRY_WAIT_MS = 5000L;

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

    @Value("${gemini.api.model:gemini-3.8-flash}")
    private String model;

    @Autowired
    public GeminiCodeReviewProvider(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            ReviewResponseValidator responseValidator,
            ReviewPromptBuilder reviewPromptBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = objectMapper;
        this.responseValidator = responseValidator;
        this.reviewPromptBuilder = reviewPromptBuilder;
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
            throw new IllegalStateException("Gemini API key is not configured");
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
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("x-goog-api-key", apiKey);
                ResponseEntity<String> response = restTemplate.postForEntity(
                        endpoint, new HttpEntity<>(requestBody, headers), String.class);
                String text = extractText(response.getBody());
                successCount.incrementAndGet();
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                log.info("gemini_request_success model={} attempts={} latencyMs={}", model, attempt, latencyMs);
                return text;
            } catch (HttpStatusCodeException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    failureCount.incrementAndGet();
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    String safeError = extractSafeErrorDetail(exception.getResponseBodyAsString());
                    log.warn("gemini_request_failed status={} model={} attempts={} latencyMs={} reason={}",
                            status, resolvedModel, attempt, latencyMs, safeError != null ? safeError : "http_error");
                    String detailSuffix = safeError != null ? ": " + safeError : "";
                    throw new IllegalStateException(
                            "Gemini review request failed with status " + status + " for model '" + resolvedModel + "'" + detailSuffix,
                            exception);
                }
                long delayMs = computeRetryDelayMs(attempt, exception.getResponseHeaders());
                retryCount.incrementAndGet();
                log.warn("gemini_retry attempt={} status={} delayMs={}", attempt, status, delayMs);
                waitBeforeRetry(delayMs);
            } catch (ResourceAccessException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    failureCount.incrementAndGet();
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    log.warn("gemini_request_failed reason=timeout_or_io attempts={} latencyMs={}", attempt, latencyMs);
                    throw new IllegalStateException("Gemini review request failed due to timeout or network error", exception);
                }
                long delayMs = computeRetryDelayMs(attempt, null);
                retryCount.incrementAndGet();
                log.warn("gemini_retry attempt={} reason=timeout_or_io delayMs={}", attempt, delayMs);
                waitBeforeRetry(delayMs);
            } catch (IllegalStateException exception) {
                failureCount.incrementAndGet();
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                log.warn("gemini_request_failed reason=invalid_response attempts={} latencyMs={}", attempt, latencyMs);
                throw exception;
            }
        }
        failureCount.incrementAndGet();
        throw new IllegalStateException("Gemini review request failed");
    }

    private String extractText(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Gemini response body was empty");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode blockReason = root.path("promptFeedback").path("blockReason");
            if (blockReason.isTextual() && !blockReason.asText().isBlank()) {
                throw new IllegalStateException("Gemini blocked the review request: " + blockReason.asText());
            }
            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isTextual() || text.asText().isBlank()) {
                throw new IllegalStateException("Gemini response did not contain structured review content");
            }
            return text.asText();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Gemini response was malformed", exception);
        }
    }

    private long computeRetryDelayMs(int attempt, HttpHeaders headers) {
        if (headers != null) {
            String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());
                    return Math.min(MAX_RETRY_WAIT_MS, Math.max(0L, seconds * 1000L));
                } catch (NumberFormatException ignored) {
                    // Fall back to exponential backoff when Retry-After is not numeric seconds
                }
            }
        }
        long exponential = BASE_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
        long jitter = ThreadLocalRandom.current().nextLong(0L, 150L);
        return Math.min(MAX_RETRY_WAIT_MS, exponential + jitter);
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
            return "gemini-3.8-flash";
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

    private void waitBeforeRetry(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini retry interrupted", exception);
        }
    }
}
