package com.codepilot.service;

import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class ReviewJobService {

    private final ReviewJobRepository reviewJobRepository;

    @Transactional
    public void updateJobStatus(UUID jobId, String status) {
        reviewJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            reviewJobRepository.save(job);
        });
    }

    @Transactional
    public ReviewJob createJob(Review review) {
        ReviewJob job = new ReviewJob();
        job.setReview(review);
        job.setStatus("PENDING");
        job.setAttemptCount(0);
        job.setCorrelationId(UUID.randomUUID().toString());
        return reviewJobRepository.save(job);
    }

    @Transactional
    public void updateStatus(ReviewJob job, String status) {
        if (!isAllowedTransition(job.getStatus(), status)) {
            throw new IllegalStateException("Invalid review job state transition");
        }
        job.setStatus(status);
        if ("COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status)) {
            job.setCompletedAt(ZonedDateTime.now());
            job.setProcessingHeartbeatAt(null);
        }
        reviewJobRepository.save(job);
    }

    @Transactional
    public void markFailed(ReviewJob job, String reason) {
        if (isTerminal(job.getStatus()) && !"FAILED".equals(job.getStatus())) {
            throw new IllegalStateException("Cannot mark terminal review job as FAILED");
        }
        job.setStatus("FAILED");
        job.setErrorMessage(safeReason(reason));
        if (job.getLastErrorCategory() == null || job.getLastErrorCategory().isBlank()) {
            job.setLastErrorCategory("PROCESSING");
        }
        job.setCompletedAt(ZonedDateTime.now());
        job.setProcessingHeartbeatAt(null);
        reviewJobRepository.save(job);
    }

    @Transactional
    public void markRetryExhausted(ReviewJob job, String reason) {
        if (isTerminal(job.getStatus()) && !"FAILED".equals(job.getStatus())) {
            throw new IllegalStateException("Cannot mark terminal review job as RETRY_EXHAUSTED");
        }
        job.setStatus("FAILED");
        job.setLastErrorCategory("RETRY_EXHAUSTED");
        String prefix = "Retries exhausted";
        String safe = safeReason(reason);
        job.setErrorMessage(safe.startsWith(prefix) ? safe : safeReason(prefix + ": " + safe));
        job.setCompletedAt(ZonedDateTime.now());
        job.setProcessingHeartbeatAt(null);
        reviewJobRepository.save(job);
    }

    public boolean isPermanentFailure(ReviewJob job) {
        if (job == null || job.getLastErrorCategory() == null) {
            return false;
        }
        return switch (job.getLastErrorCategory()) {
            case "EMPTY_DIFF",
                 "NO_REVIEWABLE_FILES",
                 "DIFF_TOO_LARGE",
                 "GITHUB_API_ERROR",
                 "GEMINI_API_ERROR",
                 "RETRY_EXHAUSTED" -> true;
            default -> false;
        };
    }

    @Transactional
    public boolean claim(UUID jobId) {
        return reviewJobRepository.claim(jobId) == 1;
    }

    @Transactional
    public int touchProcessingJobs(java.util.Collection<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }
        return reviewJobRepository.touchProcessingJobs(jobIds);
    }

    @Transactional(readOnly = true)
    public List<ReviewJob> findStaleProcessingJobs(Duration timeout) {
        return reviewJobRepository.findStaleProcessingJobs(
                "PROCESSING", ZonedDateTime.now().minus(timeout));
    }

    @Transactional
    public void recoverStaleJob(ReviewJob job, int maxAttempts) {
        if (!"PROCESSING".equals(job.getStatus())) {
            return;
        }
        if (job.getAttemptCount() >= maxAttempts) {
            job.setStatus("FAILED");
            job.setLastErrorCategory("TIMEOUT");
            job.setErrorMessage("Review processing timed out");
            job.setCompletedAt(ZonedDateTime.now());
        } else {
            job.setStatus("RETRYING");
            job.setLastErrorCategory("TIMEOUT");
            job.setErrorMessage("Worker stopped before review completed");
        }
        reviewJobRepository.save(job);
    }

    @Transactional
    public void markQueued(ReviewJob job) {
        if (!isAllowedTransition(job.getStatus(), "QUEUED")) {
            throw new IllegalStateException("Invalid review job state transition");
        }
        job.setStatus("QUEUED");
        reviewJobRepository.save(job);
    }

    @Transactional
    public void markPending(ReviewJob job) {
        if (!isAllowedTransition(job.getStatus(), "PENDING")) {
            throw new IllegalStateException("Invalid review job state transition");
        }
        job.setStatus("PENDING");
        job.setProcessingHeartbeatAt(null);
        reviewJobRepository.save(job);
    }

    @Transactional
    public void markRetrying(ReviewJob job, String reason) {
        if ("RETRYING".equals(job.getStatus())) {
            return;
        }
        if (!"PROCESSING".equals(job.getStatus()) && !"FAILED".equals(job.getStatus())) {
            throw new IllegalStateException("Invalid review job state transition");
        }
        if (isPermanentFailure(job)) {
            throw new IllegalStateException("Cannot retry permanent review job failure");
        }
        job.setStatus("RETRYING");
        job.setErrorMessage(safeReason(reason));
        if (job.getLastErrorCategory() == null || job.getLastErrorCategory().isBlank()) {
            job.setLastErrorCategory("RETRYABLE");
        }
        job.setCompletedAt(null);
        job.setProcessingHeartbeatAt(null);
        reviewJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public List<ReviewJob> findPendingJobs() {
        return reviewJobRepository.findTop50ByStatusOrderByCreatedAtAsc("PENDING");
    }

    @Transactional(readOnly = true)
    public List<ReviewJob> findStaleQueuedJobs(Duration timeout) {
        return reviewJobRepository.findStaleQueuedJobs(ZonedDateTime.now().minus(timeout));
    }

    @Transactional(readOnly = true)
    public Optional<ReviewJob> findByReviewId(UUID reviewId) {
        return reviewJobRepository.findByReviewId(reviewId);
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Review processing failed";
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
    }

    private boolean isAllowedTransition(String current, String next) {
        if (current == null) {
            return "PENDING".equals(next);
        }
        if (current.equals(next)) {
            return true;
        }
        if (isTerminal(current)) {
            return false;
        }
        return switch (current) {
            case "PENDING" -> "QUEUED".equals(next) || "PROCESSING".equals(next);
            case "QUEUED" -> "PROCESSING".equals(next) || "PENDING".equals(next);
            case "RETRYING" -> "QUEUED".equals(next) || "PROCESSING".equals(next) || "PENDING".equals(next);
            case "PROCESSING" -> "COMPLETED".equals(next)
                    || "PARTIAL".equals(next) || "FAILED".equals(next)
                    || "RETRYING".equals(next) || "PENDING".equals(next);
            default -> false;
        };
    }
}
