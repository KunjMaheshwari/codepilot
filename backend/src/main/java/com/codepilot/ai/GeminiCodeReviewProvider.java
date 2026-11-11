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
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GeminiCodeReviewProvider implements CodeReviewProvider {

    private static final int MAX_ATTEMPTS = 3;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewResponseValidator responseValidator;
    private final ReviewPromptBuilder reviewPromptBuilder;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    @Value("${gemini.api.model:gemini-1.5-pro}")
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

    @Override
    public List<ReviewFinding> performReview(String prompt) {
        throw new IllegalArgumentException("Review diff context is required for finding validation");
    }

    @Override
    public String performRawReview(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Review prompt is empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured");
        }

        String endpoint = apiUrl + "/v1beta/models/" + model + ":generateContent";
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json"));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("x-goog-api-key", apiKey);
                ResponseEntity<String> response = restTemplate.postForEntity(
                        endpoint, new HttpEntity<>(requestBody, headers), String.class);
                return extractText(response.getBody());
            } catch (HttpStatusCodeException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    log.warn("Gemini request failed with status {} after {} attempt(s)", status, attempt);
                    throw new IllegalStateException("Gemini review request failed", exception);
                }
                waitBeforeRetry(attempt);
            } catch (ResourceAccessException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Gemini request timed out or was unavailable after {} attempts", attempt);
                    throw new IllegalStateException("Gemini review request failed", exception);
                }
                waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("Gemini review request failed");
    }

    @Override
    public List<ReviewFinding> reviewDiff(String diff, List<DiffParser.ParsedFile> parsedFiles) {
        if (parsedFiles == null || parsedFiles.isEmpty()) {
            return List.of();
        }
        String prompt = reviewPromptBuilder.buildPrompt(parsedFiles);
        return responseValidator.validateAndParse(performRawReview(prompt), parsedFiles);
    }

    private String extractText(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isTextual() || text.asText().isBlank()) {
                throw new IllegalStateException("Gemini response did not contain structured review content");
            }
            return text.asText();
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Gemini response was malformed", exception);
        }
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(attempt * 250L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini retry interrupted", exception);
        }
    }
}
