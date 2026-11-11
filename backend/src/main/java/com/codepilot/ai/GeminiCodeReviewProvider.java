package com.codepilot.ai;

import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiCodeReviewProvider implements CodeReviewProvider {

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

    @Override
    public List<ReviewFinding> performReview(String prompt) {
        return List.of();
    }

    @Override
    public String performRawReview(String prompt) {
        return "";
    }

    @Override
    public List<ReviewFinding> reviewDiff(String diff, List<DiffParser.ParsedFile> parsedFiles) {
        String prompt = reviewPromptBuilder.buildPrompt(parsedFiles);
        return performReviewInternal(prompt, parsedFiles);
    }

    private List<ReviewFinding> performReviewInternal(String prompt, List<DiffParser.ParsedFile> parsedFiles) {
        int retries = 3;
        while (retries > 0) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("x-goog-api-key", apiKey);

                Map<String, Object> requestBody = Map.of(
                        "contents", List.of(
                                Map.of("parts", List.of(
                                        Map.of("text", prompt)
                                ))
                        )
                );

                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

                String endpoint = apiUrl + "/v1beta/models/" + model + ":generateContent";
                log.info("Calling Gemini API: {}", endpoint);

                ResponseEntity<String> response = restTemplate.postForEntity(endpoint, requestEntity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    // Parse Gemini Response
                    var jsonNode = objectMapper.readTree(response.getBody());
                    var candidates = jsonNode.get("candidates");
                    if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                        var content = candidates.get(0).get("content");
                        if (content != null) {
                            var parts = content.get("parts");
                            if (parts != null && parts.isArray() && !parts.isEmpty()) {
                                String aiResponseText = parts.get(0).get("text").asText();
                                return responseValidator.validateAndParse(aiResponseText, parsedFiles);
                            }
                        }
                    }
                    return List.of(); // Empty findings if no valid content
                } else if (response.getStatusCode().value() == 429) {
                    retries--;
                    if (retries > 0) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    log.error("Gemini API call failed with rate limit. No more retries.");
                    throw new RuntimeException("Rate limit exceeded");
                } else {
                    log.error("Gemini API call failed with status {}: {}", response.getStatusCode(), response.getBody());
                    throw new RuntimeException("API error: " + response.getStatusCode());
                }
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                retries--;
                if (retries > 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                log.error("Gemini API call failed with rate limit. No more retries.");
                throw new RuntimeException("Rate limit exceeded", e);
            } catch (Exception e) {
                log.error("Error communicating with Gemini API", e);
                throw new RuntimeException("Error communicating with API", e);
            }
        }
        return List.of();
    }
}
