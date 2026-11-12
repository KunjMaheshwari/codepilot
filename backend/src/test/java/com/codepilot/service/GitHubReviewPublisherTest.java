package com.codepilot.service;

import com.codepilot.ai.DiffParser;
import com.codepilot.exception.GitHubApiException;
import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.GitHubCommentPublication;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.GitHubCommentPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubReviewPublisherTest {

    @Mock
    private GitHubApiClient gitHubApiClient;

    @Mock
    private ConnectedGitHubAccountRepository connectedGitHubAccountRepository;

    @Mock
    private GitHubCommentPublicationRepository publicationRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private GitHubReviewPublisher publisher;

    private User owner;
    private Repository repository;
    private PullRequest pullRequest;
    private Review review;
    private ReviewFinding finding;
    private DiffParser.ParsedFile parsedFile;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(publisher, "publishCommentsEnabled", true);

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");

        repository = new Repository();
        repository.setId(UUID.randomUUID());
        repository.setOwner(owner);
        repository.setFullName("owner/repo");

        pullRequest = new PullRequest();
        pullRequest.setId(UUID.randomUUID());
        pullRequest.setRepository(repository);
        pullRequest.setPrNumber(42);
        pullRequest.setHeadSha("sha-123");

        review = new Review();
        review.setId(UUID.randomUUID());
        review.setPullRequest(pullRequest);
        review.setHeadSha("sha-123");

        finding = new ReviewFinding();
        finding.setId(UUID.randomUUID());
        finding.setReview(review);
        finding.setFilePath("src/Service.java");
        finding.setLineNumber(15);
        finding.setSeverity("HIGH");
        finding.setTitle("Null dereference");
        finding.setDescription("Object may be null here");
        finding.setImpact("NullPointerException at runtime");
        finding.setSuggestedFix("Objects.requireNonNull(value)");

        parsedFile = new DiffParser.ParsedFile();
        parsedFile.setFilePath("src/Service.java");
        parsedFile.getAddedLines().add(15);

        ConnectedGitHubAccount account = new ConnectedGitHubAccount();
        account.setUser(owner);
        account.setAccessToken("encrypted-token");
        org.mockito.Mockito.lenient().when(connectedGitHubAccountRepository.findByUserId(owner.getId()))
                .thenReturn(Optional.of(account));
        org.mockito.Mockito.lenient().when(encryptionService.decrypt("encrypted-token"))
                .thenReturn("raw-gh-token");
        org.mockito.Mockito.lenient().when(publicationRepository.save(any(GitHubCommentPublication.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void doesNothingWhenPublishingIsDisabledByConfiguration() {
        ReflectionTestUtils.setField(publisher, "publishCommentsEnabled", false);

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        verify(gitHubApiClient, never()).createPullRequestReviewComment(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt());
        verify(gitHubApiClient, never()).createIssueComment(
                anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void publishesInlineCommentSuccessfullyWithCommitShaAndMetadata() {
        when(publicationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(gitHubApiClient.createPullRequestReviewComment(
                eq("raw-gh-token"), eq("owner"), eq("repo"), eq(42),
                anyString(), eq("sha-123"), eq("src/Service.java"), eq(15)))
                .thenReturn(98765L);

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        ArgumentCaptor<GitHubCommentPublication> captor = ArgumentCaptor.forClass(GitHubCommentPublication.class);
        verify(publicationRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        GitHubCommentPublication saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
        assertThat(saved.getGithubCommentId()).isEqualTo(98765L);
        assertThat(publisher.getPublishedCount()).isEqualTo(1);
    }

    @Test
    void fallsBackToSummaryCommentWhenInlineDiffPositionReturns422() {
        when(publicationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(gitHubApiClient.createPullRequestReviewComment(
                eq("raw-gh-token"), eq("owner"), eq("repo"), eq(42),
                anyString(), eq("sha-123"), eq("src/Service.java"), eq(15)))
                .thenThrow(new GitHubApiException("GitHub rejected the request parameters",
                        HttpStatus.UNPROCESSABLE_ENTITY));
        when(gitHubApiClient.createIssueComment(
                eq("raw-gh-token"), eq("owner"), eq("repo"), eq(42),
                contains("src/Service.java:15")))
                .thenReturn(555L);

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        verify(gitHubApiClient).createIssueComment(
                eq("raw-gh-token"), eq("owner"), eq("repo"), eq(42),
                contains("Null dereference"));
        assertThat(publisher.getSummaryFallbackCount()).isEqualTo(1);
        assertThat(publisher.getPublishedCount()).isEqualTo(1);
    }

    @Test
    void preventsDuplicatePublicationWhenAlreadyPublished() {
        GitHubCommentPublication existing = new GitHubCommentPublication();
        existing.setStatus("PUBLISHED");
        existing.setGithubCommentId(111L);
        when(publicationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(existing));

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        verify(gitHubApiClient, never()).createPullRequestReviewComment(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt());
        assertThat(publisher.getDuplicatePreventedCount()).isEqualTo(1);
    }

    @Test
    void reconcilesAmbiguousTimeoutWithoutDoublePostingWhenCommentExistsOnGitHub() {
        GitHubCommentPublication existing = new GitHubCommentPublication();
        existing.setStatus("AMBIGUOUS_TIMEOUT");
        existing.setAttemptCount(1);
        when(publicationRepository.findByIdempotencyKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            existing.setIdempotencyKey(key);
            return Optional.of(existing);
        });
        when(gitHubApiClient.listPullRequestReviewComments("raw-gh-token", "owner", "repo", 42, 1, 100))
                .thenAnswer(inv -> List.of(Map.of(
                        "id", 7777L,
                        "body", "Comment text\n<!-- codepilot:idempotency:" + existing.getIdempotencyKey() + " -->")));

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        verify(gitHubApiClient, never()).createPullRequestReviewComment(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt());
        assertThat(existing.getStatus()).isEqualTo("PUBLISHED");
        assertThat(existing.getGithubCommentId()).isEqualTo(7777L);
        assertThat(publisher.getDuplicatePreventedCount()).isEqualTo(1);
    }

    @Test
    void marksAmbiguousTimeoutOn504GatewayTimeout() {
        when(publicationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(gitHubApiClient.createPullRequestReviewComment(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException("GitHub API comment request timed out", HttpStatus.GATEWAY_TIMEOUT));

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        ArgumentCaptor<GitHubCommentPublication> captor = ArgumentCaptor.forClass(GitHubCommentPublication.class);
        verify(publicationRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("AMBIGUOUS_TIMEOUT");
        assertThat(publisher.getFailureCount()).isEqualTo(1);
    }

    @Test
    void stopsOnUnauthorized401AndRecordsFailure() {
        when(publicationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(gitHubApiClient.createPullRequestReviewComment(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException("GitHub authentication failed", HttpStatus.UNAUTHORIZED));

        publisher.publishReviewFindings(review, pullRequest, List.of(finding), List.of(parsedFile));

        ArgumentCaptor<GitHubCommentPublication> captor = ArgumentCaptor.forClass(GitHubCommentPublication.class);
        verify(publicationRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(publisher.getFailureCount()).isEqualTo(1);
    }

    @Test
    void rejectsCrossTenantMismatchedPullRequest() {
        PullRequest otherPr = new PullRequest();
        otherPr.setId(UUID.randomUUID());
        otherPr.setRepository(repository);
        otherPr.setPrNumber(99);

        assertThatThrownBy(() -> publisher.publishReviewFindings(review, otherPr, List.of(finding), List.of(parsedFile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cross-tenant");
    }
}

