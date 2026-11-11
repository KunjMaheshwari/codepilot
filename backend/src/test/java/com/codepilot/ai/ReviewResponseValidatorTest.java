package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewResponseValidatorTest {

    private final ReviewResponseValidator validator = new ReviewResponseValidator(new ObjectMapper());

    @Test
    void validateAndParse_ShouldParseValidJSONAndMapLines() {
        String json = """
            [
                {
                    "title": "Unused Variable",
                    "description": "Variable 'z' is never used.",
                    "filePath": "src/Test.java",
                    "lineNumber": 3,
                    "category": "CODE_QUALITY",
                    "severity": "LOW"
                },
                {
                    "title": "Invalid Line",
                    "description": "This line wasn't changed.",
                    "filePath": "src/Test.java",
                    "lineNumber": 1,
                    "category": "BUG",
                    "severity": "HIGH"
                }
            ]
            """;

        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(3);

        List<ReviewFinding> findings = validator.validateAndParse(json, List.of(file));

        // Should only return the finding on the added line (line 3)
        assertThat(findings).hasSize(1);
        ReviewFinding validFinding = findings.get(0);
        assertThat(validFinding.getTitle()).isEqualTo("Unused Variable");
        assertThat(validFinding.getLineNumber()).isEqualTo(3);
        assertThat(validFinding.getCategory()).isEqualTo("CODE_QUALITY");
    }

    @Test
    void validateAndParse_ShouldHandleMarkdownJSONBlocks() {
        String json = "```json\n" + """
            [
                {
                    "title": "Test",
                    "description": "Desc",
                    "filePath": "src/Test.java",
                    "lineNumber": 2
                }
            ]
            """ + "\n```";

        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(2);

        List<ReviewFinding> findings = validator.validateAndParse(json, List.of(file));

        assertThat(findings).hasSize(1);
    }
}
