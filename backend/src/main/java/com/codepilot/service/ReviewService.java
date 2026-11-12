package com.codepilot.service;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.PageDto;
import com.codepilot.dto.ReviewDto;
import com.codepilot.dto.ReviewFindingDto;
import com.codepilot.dto.ReviewJobStatusDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.User;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final PullRequestRepository pullRequestRepository;
    private final RepositoryRepository repositoryRepository;
    private final CodeReviewOrchestrator orchestrator;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final RepositoryService repositoryService;
    private final ReviewJobService reviewJobService;
    private final ReviewJobProducer reviewJobProducer;

    @org.springframework.beans.factory.annotation.Value("${codepilot.review.async.enabled:true}")
    private boolean asyncEnabled;

    @Transactional(readOnly = true)
    public PageDto<ReviewDto> getReviews(Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        Page<Review> reviews = reviewRepository.findByPullRequestRepositoryOwnerId(user.getId(), pageable);
        return new PageDto<>(reviews.map(ReviewDto::new));
    }

    @Transactional(readOnly = true)
    public ReviewDto getReview(UUID id) {
        User user = repositoryService.getAuthenticatedUser();
        Review review = reviewRepository.findByIdAndPullRequestRepositoryOwnerId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Review", "id", id));
        return toReviewDto(review);
    }

    @Transactional(readOnly = true)
    public PageDto<ReviewFindingDto> getReviewFindings(UUID reviewId, Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        Review review = reviewRepository.findByIdAndPullRequestRepositoryOwnerId(reviewId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Review", "id", reviewId));

        Page<ReviewFinding> findings = reviewFindingRepository.findByReviewId(review.getId(), pageable);
        return new PageDto<>(findings.map(ReviewFindingDto::new));
    }

    @Transactional
    public ReviewDto createReview(UUID repositoryId, CreateReviewRequest request) {
        User user = repositoryService.getAuthenticatedUser();
        Repository repository = repositoryRepository.findByIdAndOwnerId(repositoryId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", "id", repositoryId));

        PullRequest pullRequest = pullRequestRepository.findByIdAndRepositoryOwnerId(request.getPullRequestId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("PullRequest", "id", request.getPullRequestId()));

        if (!pullRequest.getRepository().getId().equals(repository.getId())) {
            throw new IllegalArgumentException("PullRequest does not belong to the specified Repository");
        }
        if (Boolean.FALSE.equals(repository.getIsActive())) {
            throw new IllegalArgumentException("Inactive or archived repositories cannot be reviewed");
        }
        if (!"open".equalsIgnoreCase(pullRequest.getStatus())) {
            throw new IllegalArgumentException("Only open pull requests can be reviewed");
        }

        return createReview(user, repository, pullRequest);
    }

    @Transactional
    public ReviewDto createReviewForWebhook(User user, Repository repository, PullRequest pullRequest) {
        return createReview(user, repository, pullRequest);
    }

    private ReviewDto createReview(User user, Repository repository, PullRequest pullRequest) {
        java.util.Optional<Review> activeReview = reviewRepository.findFirstByPullRequestIdAndStatusIn(
                pullRequest.getId(), List.of("QUEUED", "PROCESSING", "PARTIAL"));
        if (activeReview.isPresent()
                && (pullRequest.getHeadSha() == null
                || pullRequest.getHeadSha().equals(activeReview.get().getHeadSha()))) {
            return toReviewDto(activeReview.get());
        }
        if (pullRequest.getHeadSha() != null) {
            java.util.Optional<Review> existingRevision = reviewRepository
                    .findFirstByPullRequestIdAndHeadShaAndStatusIn(
                            pullRequest.getId(), pullRequest.getHeadSha(),
                            List.of("QUEUED", "PROCESSING", "COMPLETED", "PARTIAL"));
            if (existingRevision.isPresent()) {
                return toReviewDto(existingRevision.get());
            }
        }

        Review review = new Review();
        review.setPullRequest(pullRequest);
        review.setHeadSha(pullRequest.getHeadSha());
        review.setStatus(asyncEnabled ? "QUEUED" : "PROCESSING");
        if (!asyncEnabled) {
            review.setStartedAt(ZonedDateTime.now());
        }
        review = reviewRepository.save(review);

        UUID jobId = null;
        if (asyncEnabled) {
            ReviewJob job = reviewJobService.createJob(review);
            jobId = job.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        reviewJobProducer.publish(job);
                    }
                });
            } else {
                reviewJobProducer.publish(job);
            }
        } else {
            processReview(review, pullRequest, repository, user);
        }

        return new ReviewDto(review, jobId);
    }

    private ReviewDto toReviewDto(Review review) {
        UUID jobId = reviewJobService.findByReviewId(review.getId())
                .map(ReviewJob::getId)
                .orElse(null);
        return new ReviewDto(review, jobId);
    }

    @Transactional(readOnly = true)
    public ReviewJobStatusDto getJobStatus(UUID reviewId) {
        User user = repositoryService.getAuthenticatedUser();
        Review review = reviewRepository.findByIdAndPullRequestRepositoryOwnerId(reviewId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Review", "id", reviewId));
        ReviewJob job = reviewJobService.findByReviewId(review.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ReviewJob", "reviewId", reviewId));
        return new ReviewJobStatusDto(job);
    }

    private void processReview(Review review, PullRequest pullRequest, Repository repository, User user) {
        try {
            // String diff = gitHubIntegrationService.getPullRequestDiff(user, repository, pullRequest.getPrNumber());

            // List<ReviewFinding> findings = aiReviewEngine.analyzeDiff(review, diff);
            orchestrator.executeReview(review, pullRequest, repository, user);
            // Status is updated by the orchestrator
        } catch (Exception e) {
            log.error("Error processing review", e);
            review.setStatus("FAILED");
        } finally {
            review.setCompletedAt(ZonedDateTime.now());
            reviewRepository.save(review);
        }
    }
}
