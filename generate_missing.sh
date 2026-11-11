#!/bin/bash
cat << 'JAVA' > backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java
package com.codepilot.service;

import com.codepilot.ai.CodeReviewProvider;
import com.codepilot.ai.DiffParser;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeReviewOrchestrator {
    private final CodeReviewProvider reviewProvider;
    private final GitHubIntegrationService githubService;
    private final ReviewRepository reviewRepository;
    private final ReviewFindingRepository findingRepository;
    private final ReviewJobService reviewJobService;
    private final DiffParser diffParser;

    public void orchestrateReview(ReviewJob job) {
        log.info("Starting review orchestration for job ID: {}", job.getId());
        try {
            PullRequest pr = job.getPullRequest();
            Review review = job.getReview();

            reviewJobService.updateJobStatus(job.getId(), ReviewJob.JobStatus.IN_PROGRESS);

            String diff = githubService.getPullRequestDiff(pr);
            List<DiffParser.ParsedFile> parsedFiles = diffParser.parse(diff);

            List<ReviewFinding> findings = reviewProvider.reviewDiff(diff, parsedFiles);

            if (findings != null && !findings.isEmpty()) {
                findings.forEach(f -> f.setReview(review));
                findingRepository.saveAll(findings);
            }

            review.setStatus(Review.ReviewStatus.COMPLETED);
            reviewRepository.save(review);

            reviewJobService.updateJobStatus(job.getId(), ReviewJob.JobStatus.COMPLETED);
            log.info("Review orchestration completed successfully for job ID: {}", job.getId());
        } catch (Exception e) {
            log.error("Failed to orchestrate review for job ID: {}", job.getId(), e);
            reviewJobService.updateJobStatus(job.getId(), ReviewJob.JobStatus.FAILED);

            Review review = job.getReview();
            if (review != null) {
                review.setStatus(Review.ReviewStatus.FAILED);
                reviewRepository.save(review);
            }
        }
    }
}
JAVA

cat << 'JAVA' > backend/src/main/java/com/codepilot/service/ReviewJobService.java
package com.codepilot.service;

import com.codepilot.model.ReviewJob;
import org.springframework.stereotype.Service;

@Service
public class ReviewJobService {
    public void updateJobStatus(Long jobId, ReviewJob.JobStatus status) {
        // Simple implementation to satisfy compilation
    }
}
JAVA
