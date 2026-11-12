package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewResponseValidator {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_FILE_PATH_LENGTH = 1024;
    private static final Set<String> VALID_SEVERITIES =
            Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> VALID_CATEGORIES =
            Set.of("BUG", "SECURITY", "PERFORMANCE", "RELIABILITY",
                    "MAINTAINABILITY", "CODE_QUALITY");
    private static final Set<String> VALID_CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");

    private final ObjectMapper objectMapper;
    private final AtomicLong validationFailureCount = new AtomicLong();

    public long getValidationFailureCount() {
        return validationFailureCount.get();
    }

    public List<ReviewFinding> validateAndParse(String jsonResponse, List<DiffParser.ParsedFile> originalFiles) {
        try {
            JsonNode root = parseRoot(jsonResponse);
            if (!root.isArray()) {
                throw new IllegalArgumentException("AI response must be a JSON array");
            }

            List<ReviewFinding> findings = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (JsonNode node : root) {
                ReviewFinding finding = parseFinding(node);
                if (isValidFinding(finding, originalFiles) && identities.add(identity(finding))) {
                    findings.add(finding);
                }
            }
            return findings;
        } catch (IllegalArgumentException exception) {
            validationFailureCount.incrementAndGet();
            log.warn("ai_response_validation_failed reason={}", exception.getMessage());
            throw exception;
        }
    }

    private JsonNode parseRoot(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("AI response is empty");
        }
        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            int firstNewline = normalized.indexOf('\n');
            int closing = normalized.lastIndexOf("```");
            if (firstNewline < 0 || closing <= firstNewline) {
                throw new IllegalArgumentException("AI response contains an invalid code fence");
            }
            normalized = normalized.substring(firstNewline + 1, closing).trim();
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI response is not valid JSON", exception);
        }
    }

    private ReviewFinding parseFinding(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Finding entry must be a JSON object");
        }
        String title = requiredText(node, "title");
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        String description = requiredText(node, "description");
        String filePath = validateFilePath(requiredText(node, "filePath"));
        String evidence = requiredText(node, "evidence");
        String impact = requiredText(node, "impact");
        String suggestedFix = requiredText(node, "suggestedFix");
        String severity = enumText(node, "severity", VALID_SEVERITIES);
        String category = enumText(node, "category", VALID_CATEGORIES);
        String confidence = enumText(node, "confidence", VALID_CONFIDENCE);
        JsonNode line = node.get("lineNumber");
        if (line == null || !line.canConvertToInt() || line.asInt() < 1) {
            throw new IllegalArgumentException("lineNumber must be a positive integer");
        }

        ReviewFinding finding = new ReviewFinding();
        finding.setTitle(title);
        finding.setMessage(description);
        finding.setDescription(description);
        finding.setFilePath(filePath);
        finding.setLineNumber(line.asInt());
        finding.setCategory(category);
        finding.setSeverity(severity);
        finding.setEvidence(evidence);
        finding.setImpact(impact);
        finding.setSuggestedFix(suggestedFix);
        finding.setConfidence(confidence);
        return finding;
    }

    private String validateFilePath(String filePath) {
        if (filePath.length() > MAX_FILE_PATH_LENGTH
                || filePath.startsWith("/")
                || filePath.contains("\\")
                || filePath.contains("\0")
                || filePath.contains("..")) {
            throw new IllegalArgumentException("Invalid filePath in finding");
        }
        return filePath;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.asText().trim();
    }

    private String enumText(JsonNode node, String field, Set<String> allowed) {
        String value = requiredText(node, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private boolean isValidFinding(ReviewFinding finding, List<DiffParser.ParsedFile> files) {
        DiffParser.ParsedFile file = files.stream()
                .filter(candidate -> candidate.getFilePath().equals(finding.getFilePath()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Finding references an unknown file"));
        if (!file.getAddedLines().contains(finding.getLineNumber())) {
            throw new IllegalArgumentException("Finding references a non-added line");
        }
        return true;
    }

    private String identity(ReviewFinding finding) {
        String normalizedTitle = finding.getTitle().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return String.join("|", finding.getFilePath(), String.valueOf(finding.getLineNumber()),
                normalizedTitle, finding.getSeverity());
    }
}
