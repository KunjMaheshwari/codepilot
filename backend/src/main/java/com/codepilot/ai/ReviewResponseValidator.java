package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ReviewResponseValidator {

    private static final Set<String> VALID_SEVERITIES =
            Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> VALID_CATEGORIES =
            Set.of("BUG", "SECURITY", "PERFORMANCE", "RELIABILITY",
                    "MAINTAINABILITY", "CODE_QUALITY");
    private static final Set<String> VALID_CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");

    private final ObjectMapper objectMapper;

    public List<ReviewFinding> validateAndParse(String jsonResponse, List<DiffParser.ParsedFile> originalFiles) {
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
        String title = requiredText(node, "title");
        String description = requiredText(node, "description");
        String filePath = requiredText(node, "filePath");
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
        return String.join("|", finding.getFilePath(), String.valueOf(finding.getLineNumber()),
                finding.getTitle().toLowerCase(Locale.ROOT), finding.getSeverity());
    }
}
