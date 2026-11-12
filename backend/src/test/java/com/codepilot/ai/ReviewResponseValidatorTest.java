package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                    "severity": "LOW",
                    "evidence": "z",
                    "impact": "None",
                    "suggestedFix": "Remove z",
                    "confidence": "HIGH"
                },
                {
                    "title": "Invalid Line",
                    "description": "This line wasn't changed.",
                    "filePath": "src/Test.java",
                    "lineNumber": 1,
                    "category": "BUG",
                    "severity": "HIGH",
                    "evidence": "unchanged",
                    "impact": "None",
                    "suggestedFix": "No change",
                    "confidence": "HIGH"
                }
            ]
            """;

        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(3);

        assertThatThrownBy(() -> validator.validateAndParse(json, List.of(file)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Finding references a non-added line");
    }

    @Test
    void validateAndParse_ShouldHandleMarkdownJSONBlocks() {
        String json = "```json\n" + """
            [
                {
                    "title": "Test",
                    "description": "Desc",
                    "filePath": "src/Test.java",
                    "lineNumber": 2,
                    "category": "BUG",
                    "severity": "LOW",
                    "evidence": "changed code",
                    "impact": "Potential defect",
                    "suggestedFix": "Correct the code",
                    "confidence": "MEDIUM"
                }
            ]
            """ + "\n```";

        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(2);

        List<ReviewFinding> findings = validator.validateAndParse(json, List.of(file));

        assertThat(findings).hasSize(1);
    }

    @Test
    void rejectsInvalidSchemaAndDuplicateFindings() {
        String json = """
                [
                  {
                    "title": "Bug",
                    "description": "Problem",
                    "category": "BUG",
                    "severity": "HIGH",
                    "filePath": "src/Test.java",
                    "lineNumber": 2,
                    "evidence": "bad",
                    "impact": "failure",
                    "suggestedFix": "fix it",
                    "confidence": "HIGH"
                  },
                  {
                    "title": "Bug",
                    "description": "Problem",
                    "category": "BUG",
                    "severity": "HIGH",
                    "filePath": "src/Test.java",
                    "lineNumber": 2,
                    "evidence": "bad",
                    "impact": "failure",
                    "suggestedFix": "fix it",
                    "confidence": "HIGH"
                  }
                ]
                """;
        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(2);

        assertThat(validator.validateAndParse(json, List.of(file))).hasSize(1);
        assertThatThrownBy(() -> validator.validateAndParse(json.replace("\"HIGH\"", "\"INVALID\""),
                List.of(file))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedJsonAndMissingRequiredFields() {
        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(2);

        assertThatThrownBy(() -> validator.validateAndParse("{not-json", List.of(file)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("{\"title\":\"not-an-array\"}", List.of(file)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("[{\"title\":\"Missing fields\"}]", List.of(file)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(validator.getValidationFailureCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void rejectsInvalidCategoryAndTraversalFilePaths() {
        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        file.getAddedLines().add(2);

        String invalidCategory = """
                [{"title":"T","description":"D","category":"UNKNOWN","severity":"HIGH","filePath":"src/Test.java","lineNumber":2,"evidence":"e","impact":"i","suggestedFix":"f","confidence":"HIGH"}]
                """;
        String traversalPath = """
                [{"title":"T","description":"D","category":"BUG","severity":"HIGH","filePath":"../etc/passwd","lineNumber":2,"evidence":"e","impact":"i","suggestedFix":"f","confidence":"HIGH"}]
                """;
        String absolutePath = """
                [{"title":"T","description":"D","category":"BUG","severity":"HIGH","filePath":"/src/Test.java","lineNumber":2,"evidence":"e","impact":"i","suggestedFix":"f","confidence":"HIGH"}]
                """;

        assertThatThrownBy(() -> validator.validateAndParse(invalidCategory, List.of(file)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse(traversalPath, List.of(file)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid filePath in finding");
        assertThatThrownBy(() -> validator.validateAndParse(absolutePath, List.of(file)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid filePath in finding");
    }
}
