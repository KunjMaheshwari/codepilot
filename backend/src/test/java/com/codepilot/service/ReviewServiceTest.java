package com.codepilot.service;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.ReviewDto;
import com.codepilot.dto.ReviewJobStatusDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.model.User;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ReviewFindingRepository reviewFindingRepository;
    @Mock
    private PullRequestRepository pullRequestRepository;
    @Mock
    private RepositoryRepository repositoryRepository;
    @Mock
    private CodeReviewOrchestrator orchestrator;
    @Mock
    private RepositoryService repositoryService;
    @Mock
    private ReviewJobService reviewJobService;
    @Mock
    private ReviewJobProducer reviewJobProducer;

    @InjectMocks
    private ReviewService reviewService;

    @Test
    void createReviewQueuesAsyncJobWhenNoActiveOrCompletedRevisionExists() {
        ReflectionTestUtils.setField(reviewService, "asyncEnabled", true);
        User owner = user();
        Repository repo = activeRepo(owner);
        PullRequest pr = openPr(repo, "abc123");

        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(repositoryRepository.findByIdAndOwnerId(repo.getId(), owner.getId())).thenReturn(Optional.of(repo));
        when(pullRequestRepository.findByIdAndRepositoryOwnerId(pr.getId(), owner.getId())).thenReturn(Optional.of(pr));
        when(reviewRepository.findFirstByPullRequestIdAndStatusIn(any(), any())).thenReturn(Optional.empty());
        when(reviewRepository.findFirstByPullRequestIdAndHeadShaAndStatusIn(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ReviewJob job = new ReviewJob();
        job.setId(UUID.randomUUID());
        when(reviewJobService.createJob(any(Review.class))).thenReturn(job);

        CreateReviewRequest req = new CreateReviewRequest();
        req.setPullRequestId(pr.getId());

        ReviewDto dto = reviewService.createReview(repo.getId(), req);

        assertThat(dto.getStatus()).isEqualTo("QUEUED");
        assertThat(dto.getJobId()).isEqualTo(job.getId());
        verify(reviewJobProducer).publish(job);
    }

    @Test
    void createReviewReusesActiveReviewForSameHeadSha() {
        User owner = user();
        Repository repo = activeRepo(owner);
        PullRequest pr = openPr(repo, "sha-1");

        Review existing = new Review();
        existing.setId(UUID.randomUUID());
        existing.setPullRequest(pr);
        existing.setHeadSha("sha-1");
        existing.setStatus("PROCESSING");

        ReviewJob existingJob = new ReviewJob();
        existingJob.setId(UUID.randomUUID());

        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(repositoryRepository.findByIdAndOwnerId(repo.getId(), owner.getId())).thenReturn(Optional.of(repo));
        when(pullRequestRepository.findByIdAndRepositoryOwnerId(pr.getId(), owner.getId())).thenReturn(Optional.of(pr));
        when(reviewRepository.findFirstByPullRequestIdAndStatusIn(pr.getId(), List.of("QUEUED", "PROCESSING", "PARTIAL")))
                .thenReturn(Optional.of(existing));
        when(reviewJobService.findByReviewId(existing.getId())).thenReturn(Optional.of(existingJob));

        CreateReviewRequest req = new CreateReviewRequest();
        req.setPullRequestId(pr.getId());

        ReviewDto dto = reviewService.createReview(repo.getId(), req);

        assertThat(dto.getId()).isEqualTo(existing.getId());
        assertThat(dto.getJobId()).isEqualTo(existingJob.getId());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReviewRejectsClosedPullRequestAndInactiveRepository() {
        User owner = user();
        Repository inactiveRepo = activeRepo(owner);
        inactiveRepo.setIsActive(false);
        PullRequest openPr = openPr(inactiveRepo, "sha");

        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(repositoryRepository.findByIdAndOwnerId(inactiveRepo.getId(), owner.getId()))
                .thenReturn(Optional.of(inactiveRepo));
        when(pullRequestRepository.findByIdAndRepositoryOwnerId(openPr.getId(), owner.getId()))
                .thenReturn(Optional.of(openPr));

        CreateReviewRequest req = new CreateReviewRequest();
        req.setPullRequestId(openPr.getId());

        assertThatThrownBy(() -> reviewService.createReview(inactiveRepo.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inactive or archived");

        inactiveRepo.setIsActive(true);
        openPr.setStatus("closed");
        assertThatThrownBy(() -> reviewService.createReview(inactiveRepo.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only open pull requests");
    }

    @Test
    void getJobStatusThrowsNotFoundWhenReviewOrJobMissing() {
        User owner = user();
        UUID reviewId = UUID.randomUUID();
        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(reviewRepository.findByIdAndPullRequestRepositoryOwnerId(reviewId, owner.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getJobStatus(reviewId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getJobStatusReturnsDtoWhenOwned() {
        User owner = user();
        Repository repo = activeRepo(owner);
        PullRequest pr = openPr(repo, "sha");
        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setPullRequest(pr);

        ReviewJob job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setReview(review);
        job.setStatus("QUEUED");
        job.setAttemptCount(0);

        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(reviewRepository.findByIdAndPullRequestRepositoryOwnerId(review.getId(), owner.getId()))
                .thenReturn(Optional.of(review));
        when(reviewJobService.findByReviewId(review.getId())).thenReturn(Optional.of(job));

        ReviewJobStatusDto status = reviewService.getJobStatus(review.getId());
        assertThat(status.getStatus()).isEqualTo("QUEUED");
        assertThat(status.getJobId()).isEqualTo(job.getId());
    }

    private User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        return u;
    }

    private Repository activeRepo(User owner) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setOwner(owner);
        r.setIsActive(true);
        return r;
    }

    private PullRequest openPr(Repository repo, String headSha) {
        PullRequest pr = new PullRequest();
        pr.setId(UUID.randomUUID());
        pr.setRepository(repo);
        pr.setStatus("open");
        pr.setHeadSha(headSha);
        return pr;
    }
}
