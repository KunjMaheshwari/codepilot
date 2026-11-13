package com.codepilot.service;

import com.codepilot.ai.CodeReviewProvider;
import com.codepilot.ai.DiffParser;
import com.codepilot.exception.GeminiApiException;
import com.codepilot.exception.GitHubApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
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
    private final GitHubReviewPublisher gitHubReviewPublisher;

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
            review.setStatus("PROCESSING");
            if (review.getStartedAt() == null) {
                review.setStartedAt(ZonedDateTime.now());
            }

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
                List<ReviewFinding> savedFindings = findingRepository.saveAll(findings);
                gitHubReviewPublisher.publishReviewFindings(review, pr, savedFindings, reviewableFiles);
            }

            review.setStatus(reviewableFiles.size() == parsedFiles.size() ? "COMPLETED" : "PARTIAL");
            review.setCompletedAt(ZonedDateTime.now());
            reviewRepository.save(review);

            reviewJobService.updateStatus(job, review.getStatus());
            log.info("Review orchestration completed jobId={} reviewId={} status={} correlationId={}",
                    job.getId(), review.getId(), review.getStatus(), job.getCorrelationId());
        } catch (Exception e) {
            FailureClassification classification = classifyFailure(e);
            log.error("Failed to orchestrate review jobId={} correlationId={} category={} reason={}",
                    job.getId(), job.getCorrelationId(), classification.category(), classification.message());
            job.setLastErrorCategory(classification.category());
            if (e instanceof GeminiApiException geminiEx) {
                job.setRetryAfterSeconds(geminiEx.getRetryAfterSeconds());
            }
            reviewJobService.markFailed(job, classification.message());

            Review review = job.getReview();
            if (review != null) {
                review.setStatus("FAILED");
                review.setCompletedAt(ZonedDateTime.now());
                reviewRepository.save(review);
            }
        }
    }

    private FailureClassification classifyFailure(Exception e) {
        String message = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : "Review processing failed";

        if (e instanceof GeminiApiException geminiEx) {
            return new FailureClassification(geminiEx.getErrorCategory(), message);
        }

        if (e instanceof GitHubApiException githubEx) {
            if (githubEx.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY
                    && message.toLowerCase().contains("empty pull request diff")) {
                return new FailureClassification("EMPTY_DIFF", "Pull request diff is empty or unavailable");
            }
            HttpStatus status = githubEx.getStatus();
            boolean transientGitHub = status != null && (status == HttpStatus.TOO_MANY_REQUESTS
                    || status == HttpStatus.FORBIDDEN && message.toLowerCase().contains("rate limit")
                    || status.is5xxServerError());
            return new FailureClassification(
                    transientGitHub ? "GITHUB_API_TRANSIENT" : "GITHUB_API_ERROR",
                    message);
        }

        String lower = message.toLowerCase();
        if (lower.contains("pull request diff is empty") || lower.contains("empty pull request diff")) {
            return new FailureClassification("EMPTY_DIFF", "Pull request diff is empty or unavailable");
        }
        if (lower.contains("no reviewable source files")) {
            return new FailureClassification("NO_REVIEWABLE_FILES", "Pull request diff has no supported source files");
        }
        if (lower.contains("exceeds the review size limit")) {
            return new FailureClassification("DIFF_TOO_LARGE", "Pull request diff exceeds the review size limit");
        }
        if (lower.startsWith("ai response")
                || lower.startsWith("finding ")
                || lower.startsWith("invalid filepath")
                || lower.startsWith("missing required field")
                || lower.startsWith("linenumber must be")
                || lower.contains("gemini response")) {
            return new FailureClassification("INVALID_AI_RESPONSE", message);
        }
        if (lower.contains("gemini api key")
                || lower.contains("gemini blocked")
                || lower.contains("gemini review request failed with status 400")
                || lower.contains("gemini review request failed with status 401")
                || lower.contains("gemini review request failed with status 403")
                || lower.contains("gemini review request failed with status 404")) {
            return new FailureClassification("GEMINI_API_ERROR", message);
        }
        if (lower.contains("gemini")) {
            return new FailureClassification("GEMINI_API_TRANSIENT", message);
        }
        return new FailureClassification("PROCESSING", message);
    }

    private record FailureClassification(String category, String message) {}
}
