package com.codepilot.integration;

import com.codepilot.ai.DiffParser;
import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.GitHubCommentPublication;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.GitHubCommentPublicationRepository;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.EncryptionService;
import com.codepilot.service.GitHubApiClient;
import com.codepilot.service.GitHubReviewPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GitHubCommentPublicationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("codepilot.review.publish-comments-enabled", () -> "true");
        registry.add("codepilot.review.async.enabled", () -> "false");
    }

    @Autowired
    private GitHubReviewPublisher publisher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConnectedGitHubAccountRepository accountRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewFindingRepository findingRepository;

    @Autowired
    private GitHubCommentPublicationRepository publicationRepository;

    @Autowired
    private EncryptionService encryptionService;

    @MockBean
    private GitHubApiClient gitHubApiClient;

    private PullRequest pullRequest;
    private Review firstReview;
    private Review retryReview;
    private ReviewFinding firstFinding;
    private ReviewFinding retryFinding;

    @BeforeEach
    void setUp() {
        publicationRepository.deleteAll();
        findingRepository.deleteAll();
        reviewRepository.deleteAll();
        pullRequestRepository.deleteAll();
        repositoryRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User owner = new User();
        owner.setUsername("pub-owner-" + UUID.randomUUID());
        owner.setEmail("pub-" + UUID.randomUUID() + "@example.com");
        owner = userRepository.save(owner);

        ConnectedGitHubAccount account = new ConnectedGitHubAccount();
        account.setUser(owner);
        account.setGithubUserId("30001");
        account.setGithubUsername(owner.getUsername());
        account.setAccessToken(encryptionService.encrypt("gho_pub_token"));
        accountRepository.save(account);

        Repository repo = new Repository();
        repo.setOwner(owner);
        repo.setGithubRepoId("repo-" + UUID.randomUUID());
        repo.setName("app");
        repo.setFullName("owner/app");
        repo = repositoryRepository.save(repo);

        pullRequest = new PullRequest();
        pullRequest.setRepository(repo);
        pullRequest.setPrNumber(7);
        pullRequest.setTitle("Fix bug");
        pullRequest.setAuthor("dev");
        pullRequest.setStatus("open");
        pullRequest.setHeadSha("head-sha-7");
        pullRequest = pullRequestRepository.save(pullRequest);

        firstReview = new Review();
        firstReview.setPullRequest(pullRequest);
        firstReview.setHeadSha("head-sha-7");
        firstReview.setStatus("COMPLETED");
        firstReview = reviewRepository.save(firstReview);

        firstFinding = new ReviewFinding();
        firstFinding.setReview(firstReview);
        firstFinding.setFilePath("src/Main.java");
        firstFinding.setLineNumber(12);
        firstFinding.setSeverity("HIGH");
        firstFinding.setTitle("Resource leak");
        firstFinding.setMessage("Close stream");
        firstFinding.setDescription("Close stream");
        firstFinding = findingRepository.save(firstFinding);

        retryReview = new Review();
        retryReview.setPullRequest(pullRequest);
        retryReview.setHeadSha("head-sha-7");
        retryReview.setStatus("COMPLETED");
        retryReview = reviewRepository.save(retryReview);

        retryFinding = new ReviewFinding();
        retryFinding.setReview(retryReview);
        retryFinding.setFilePath("src/Main.java");
        retryFinding.setLineNumber(12);
        retryFinding.setSeverity("HIGH");
        retryFinding.setTitle("Resource leak");
        retryFinding.setMessage("Close stream");
        retryFinding.setDescription("Close stream");
        retryFinding = findingRepository.save(retryFinding);
    }

    @Test
    void persistsPublicationAndPreventsDuplicatesAcrossRetriesAndReprocessedHeadSha() {
        DiffParser.ParsedFile file = new DiffParser.ParsedFile();
        file.setFilePath("src/Main.java");
        file.getAddedLines().add(12);

        when(gitHubApiClient.createPullRequestReviewComment(
                eq("gho_pub_token"), eq("owner"), eq("app"), eq(7),
                anyString(), eq("head-sha-7"), eq("src/Main.java"), eq(12)))
                .thenReturn(4242L);

        publisher.publishReviewFindings(firstReview, pullRequest, List.of(firstFinding), List.of(file));
        // Simulates worker restart / retry or duplicate webhook for the same PR head SHA
        publisher.publishReviewFindings(retryReview, pullRequest, List.of(retryFinding), List.of(file));

        verify(gitHubApiClient, times(1)).createPullRequestReviewComment(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt());

        List<GitHubCommentPublication> publications = publicationRepository.findAll();
        assertThat(publications).hasSize(1);
        assertThat(publications.get(0).getStatus()).isEqualTo("PUBLISHED");
        assertThat(publications.get(0).getGithubCommentId()).isEqualTo(4242L);
    }
}

