package com.codepilot.dto;

import com.codepilot.model.ReviewFinding;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
public class ReviewFindingDto {
    private UUID id;
    private UUID reviewId;
    private String filePath;
    private Integer lineNumber;
    private String severity;
    private String message;

    public ReviewFindingDto(ReviewFinding finding) {
        this.id = finding.getId();
        this.reviewId = finding.getReview() != null ? finding.getReview().getId() : null;
        this.filePath = finding.getFilePath();
        this.lineNumber = finding.getLineNumber();
        this.severity = finding.getSeverity();
        this.message = finding.getMessage();
    }
}
