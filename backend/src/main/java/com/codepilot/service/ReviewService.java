package com.codepilot.service;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.PageDto;
import com.codepilot.dto.ReviewDto;
import com.codepilot.dto.ReviewFindingDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
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
    private final MockAiReviewEngine aiReviewEngine;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final RepositoryService repositoryService;

    public PageDto<ReviewDto> getReviews(Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        Page<Review> reviews = reviewRepository.findByPullRequestRepositoryOwnerId(user.getId(), pageable);
        return new PageDto<>(reviews.map(ReviewDto::new));
    }

    public ReviewDto getReview(UUID id) {
        User user = repositoryService.getAuthenticatedUser();
        Review review = reviewRepository.findByIdAndPullRequestRepositoryOwnerId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Review", "id", id));
        return new ReviewDto(review);
    }

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

        Review review = new Review();
        review.setPullRequest(pullRequest);
        review.setStatus("PROCESSING");
        review.setStartedAt(ZonedDateTime.now());
        review = reviewRepository.save(review);

        // Sync execution for foundation (would be async in real app)
        processReview(review, pullRequest, repository, user);

        return new ReviewDto(review);
    }

    private void processReview(Review review, PullRequest pullRequest, Repository repository, User user) {
        try {
            String diff = gitHubIntegrationService.getPullRequestDiff(user, repository, pullRequest.getPrNumber());

            List<ReviewFinding> findings = aiReviewEngine.analyzeDiff(review, diff);
            reviewFindingRepository.saveAll(findings);
            review.setStatus("COMPLETED");
        } catch (Exception e) {
            log.error("Error processing review", e);
            review.setStatus("FAILED");
        } finally {
            review.setCompletedAt(ZonedDateTime.now());
            reviewRepository.save(review);
        }
    }
}
