package com.codepilot.ai;

import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.ReviewJob;
import com.codepilot.model.User;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import com.codepilot.service.GitHubIntegrationService;
import com.codepilot.service.GitHubReviewPublisher;
import com.codepilot.service.ReviewJobService;
import com.codepilot.service.CodeReviewOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeReviewOrchestratorTest {

    @Mock
    private GitHubIntegrationService gitHubIntegrationService;

    @Mock
    private DiffParser diffParser;

    @Mock
    private CodeReviewProvider reviewProvider;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewFindingRepository findingRepository;

    @Mock
    private ReviewJobService reviewJobService;

    @Mock
    private GitHubReviewPublisher gitHubReviewPublisher;

    @InjectMocks
    private CodeReviewOrchestrator orchestrator;

    private Review review;
    private PullRequest pr;
    private Repository repo;
    private User user;
    private ReviewJob job;

    @BeforeEach
    void setUp() {
        review = new Review();
        pr = new PullRequest();
        pr.setPrNumber(1);
        repo = new Repository();
        user = new User();

        repo.setOwner(user);
        pr.setRepository(repo);
        review.setPullRequest(pr);

        job = new ReviewJob();
        job.setReview(review);
    }

    @Test
    void executeReview_ShouldProcessAndCompleteSuccessfully() {
        when(reviewJobService.createJob(review)).thenReturn(job);
        when(gitHubIntegrationService.getPullRequestDiff(user, repo, 1)).thenReturn("diff");

        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Test.java");
        when(diffParser.parseDiff("diff")).thenReturn(List.of(file));

        ReviewFinding finding = new ReviewFinding();
        finding.setFilePath("src/Test.java");
        finding.setLineNumber(1);
        finding.setTitle("Title");

        when(reviewProvider.reviewDiff(eq("diff"), any())).thenReturn(List.of(finding));

        orchestrator.executeReview(review, pr, repo, user);

        verify(findingRepository).saveAll(any());
        verify(reviewRepository).save(review);
        verify(reviewJobService).updateStatus(job, "COMPLETED");
    }

    @Test
    void executeReview_ShouldHandleEmptyDiffs() {
        when(reviewJobService.createJob(review)).thenReturn(job);
        when(gitHubIntegrationService.getPullRequestDiff(user, repo, 1)).thenReturn("");
        when(diffParser.parseDiff("")).thenReturn(List.of()); // No actionable files

        orchestrator.executeReview(review, pr, repo, user);

        verify(reviewProvider, never()).reviewDiff(anyString(), any());
        verify(reviewRepository).save(review);
    }

    @Test
    void executeReview_ShouldHandleExceptionsAndMarkFailed() {
        when(reviewJobService.createJob(review)).thenReturn(job);
        when(gitHubIntegrationService.getPullRequestDiff(user, repo, 1))
                .thenThrow(new RuntimeException("GitHub API Error"));

        orchestrator.executeReview(review, pr, repo, user);

        verify(reviewJobService).markFailed(eq(job), anyString());
        verify(reviewRepository).save(review);
    }

    @Test
    void executeReview_ClassifiesNoReviewableFilesAsPermanentCategory() {
        when(reviewJobService.createJob(review)).thenReturn(job);
        when(gitHubIntegrationService.getPullRequestDiff(user, repo, 1)).thenReturn("diff");

        DiffParser.ParsedFile nonReviewable = new DiffParser.ParsedFile();
        nonReviewable.setFilePath("README.md");
        nonReviewable.setReviewable(false);
        when(diffParser.parseDiff("diff")).thenReturn(List.of(nonReviewable));

        orchestrator.executeReview(review, pr, repo, user);

        verify(reviewJobService).markFailed(eq(job), eq("Pull request diff has no supported source files"));
        org.assertj.core.api.Assertions.assertThat(job.getLastErrorCategory()).isEqualTo("NO_REVIEWABLE_FILES");
    }

    @Test
    void executeReview_ClassifiesGitHubApiErrorsByRetryability() {
        when(reviewJobService.createJob(review)).thenReturn(job);
        when(gitHubIntegrationService.getPullRequestDiff(user, repo, 1))
                .thenThrow(new com.codepilot.exception.GitHubApiException(
                        "GitHub repository not found", org.springframework.http.HttpStatus.NOT_FOUND));

        orchestrator.executeReview(review, pr, repo, user);

        verify(reviewJobService).markFailed(eq(job), eq("GitHub repository not found"));
        org.assertj.core.api.Assertions.assertThat(job.getLastErrorCategory()).isEqualTo("GITHUB_API_ERROR");
    }
}
