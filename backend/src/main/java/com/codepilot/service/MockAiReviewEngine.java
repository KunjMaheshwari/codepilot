package com.codepilot.service;

import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class MockAiReviewEngine {

    public List<ReviewFinding> analyzeDiff(Review review, String diffContent) {
        log.info("Mock AI analyzing diff for review {}", review.getId());

        ReviewFinding finding = new ReviewFinding();
        finding.setReview(review);
        finding.setFilePath("src/main/java/MockFile.java");
        finding.setLineNumber(10);
        finding.setSeverity("WARNING");
        finding.setMessage("Consider using a more descriptive variable name.");

        return Collections.singletonList(finding);
    }
}
