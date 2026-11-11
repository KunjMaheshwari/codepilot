package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewResponseValidator {

    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> VALID_CATEGORIES = Set.of("BUG", "SECURITY", "PERFORMANCE", "RELIABILITY", "MAINTAINABILITY", "CODE_QUALITY");

    public List<ReviewFinding> validateAndParse(String jsonResponse, List<DiffParser.ParsedFile> originalFiles) {
        List<ReviewFinding> validFindings = new ArrayList<>();

        try {
            if (jsonResponse.startsWith("```json")) {
                jsonResponse = jsonResponse.substring(7);
                if (jsonResponse.endsWith("```")) {
                    jsonResponse = jsonResponse.substring(0, jsonResponse.length() - 3);
                }
            } else if (jsonResponse.startsWith("```")) {
                jsonResponse = jsonResponse.substring(3);
                if (jsonResponse.endsWith("```")) {
                    jsonResponse = jsonResponse.substring(0, jsonResponse.length() - 3);
                }
            }

            JsonNode rootNode = objectMapper.readTree(jsonResponse.trim());
            if (!rootNode.isArray()) {
                log.error("AI response is not a JSON array.");
                return validFindings;
            }

            for (JsonNode findingNode : rootNode) {
                try {
                    ReviewFinding finding = parseFinding(findingNode);
                    if (isValidFinding(finding, originalFiles)) {
                        validFindings.add(finding);
                    }
                } catch (Exception e) {
                    log.warn("Skipping malformed finding: {}", e.getMessage());
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI JSON response", e);
        }

        return validFindings;
    }

    private ReviewFinding parseFinding(JsonNode node) {
        ReviewFinding finding = new ReviewFinding();

        if (!node.hasNonNull("title") || !node.hasNonNull("description") || !node.hasNonNull("filePath") || !node.hasNonNull("lineNumber")) {
            throw new IllegalArgumentException("Missing required fields");
        }

        finding.setTitle(node.get("title").asText());
        finding.setMessage(node.get("description").asText());
        finding.setDescription(node.get("description").asText());
        finding.setFilePath(node.get("filePath").asText());
        finding.setLineNumber(node.get("lineNumber").asInt());

        String category = node.hasNonNull("category") ? node.get("category").asText() : "CODE_QUALITY";
        finding.setCategory(VALID_CATEGORIES.contains(category) ? category : "CODE_QUALITY");

        String severity = node.hasNonNull("severity") ? node.get("severity").asText() : "INFO";
        finding.setSeverity(VALID_SEVERITIES.contains(severity) ? severity : "INFO");

        if (node.hasNonNull("evidence")) finding.setEvidence(node.get("evidence").asText());
        if (node.hasNonNull("impact")) finding.setImpact(node.get("impact").asText());
        if (node.hasNonNull("suggestedFix")) finding.setSuggestedFix(node.get("suggestedFix").asText());
        if (node.hasNonNull("confidence")) finding.setConfidence(node.get("confidence").asText());

        return finding;
    }

    private boolean isValidFinding(ReviewFinding finding, List<DiffParser.ParsedFile> originalFiles) {
        for (DiffParser.ParsedFile file : originalFiles) {
            if (file.getFilePath().equals(finding.getFilePath())) {
                if (file.getAddedLines().contains(finding.getLineNumber())) {
                    return true;
                } else {
                    log.warn("Line number {} is not valid for file {}", finding.getLineNumber(), finding.getFilePath());
                    return false;
                }
            }
        }
        log.warn("File {} not found in parsed diffs", finding.getFilePath());
        return false;
    }
}
