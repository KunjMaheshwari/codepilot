package com.codepilot.ai;

import com.codepilot.model.ReviewFinding;

import java.util.List;

public interface CodeReviewProvider {
    List<ReviewFinding> performReview(String prompt);
    String performRawReview(String prompt);
    List<ReviewFinding> reviewDiff(String diff, List<DiffParser.ParsedFile> parsedFiles);
}
