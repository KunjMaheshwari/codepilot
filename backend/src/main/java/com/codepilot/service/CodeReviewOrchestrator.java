package com.codepilot.service;

import com.codepilot.ai.CodeReviewProvider;
import com.codepilot.ai.DiffParser;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public void executeReview(Review review, PullRequest pr, Repository repo, User user) {
        ReviewJob job = reviewJobService.createJob(review);
        orchestrateReview(job);
    }

    public void orchestrateReview(ReviewJob job) {
        log.info("Starting review orchestration for job ID: {} correlationId={}",
                job.getId(), job.getCorrelationId());
        try {
            Review review = job.getReview();
            PullRequest pr = review.getPullRequest();

            reviewJobService.updateStatus(job, "PROCESSING");

            String diff = githubService.getPullRequestDiff(pr.getRepository().getOwner(), pr.getRepository(), pr.getPrNumber());
            List<DiffParser.ParsedFile> parsedFiles = diffParser.parseDiff(diff);

            if (parsedFiles == null || parsedFiles.isEmpty()) {
                throw new IllegalStateException("Pull request diff is empty or unavailable");
            }

            List<DiffParser.ParsedFile> reviewableFiles = parsedFiles.stream()
                    .filter(DiffParser.ParsedFile::isReviewable)
                    .toList();
            if (reviewableFiles.isEmpty()) {
                throw new IllegalStateException("Pull request diff has no reviewable source files");
            }

            List<ReviewFinding> findings = reviewProvider.reviewDiff(diff, reviewableFiles);

            if (findings != null && !findings.isEmpty()) {
                findings.forEach(f -> f.setReview(review));
                findingRepository.saveAll(findings);
            }

            review.setStatus(reviewableFiles.size() == parsedFiles.size() ? "COMPLETED" : "PARTIAL");
            reviewRepository.save(review);

            reviewJobService.updateStatus(job, review.getStatus());
            log.info("Review orchestration completed successfully for job ID: {} correlationId={}",
                    job.getId(), job.getCorrelationId());
        } catch (Exception e) {
            log.error("Failed to orchestrate review for job ID: {} correlationId={}",
                    job.getId(), job.getCorrelationId(), e);
            reviewJobService.markFailed(job, e.getMessage());

            Review review = job.getReview();
            if (review != null) {
                review.setStatus("FAILED");
                reviewRepository.save(review);
            }
        }
    }
}
