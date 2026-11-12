package com.codepilot.infrastructure;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.ReviewDto;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.model.User;
import com.codepilot.model.WebhookDelivery;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewJobRepository;
import com.codepilot.repository.ReviewRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.repository.WebhookDeliveryRepository;
import com.codepilot.service.GitHubWebhookService;
import com.codepilot.service.ReviewJobProducer;
import com.codepilot.service.ReviewJobService;
import com.codepilot.service.ReviewJobWorker;
import com.codepilot.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AsyncReliabilityIntegrationTest {

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
        registry.add("codepilot.review.async.enabled", () -> "false");
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RepositoryRepository repositoryRepository;
    @Autowired
    private PullRequestRepository pullRequestRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewJobService reviewJobService;
    @Autowired
    private ReviewJobProducer reviewJobProducer;
    @Autowired
    private ReviewJobWorker reviewJobWorker;
    @Autowired
    private GitHubWebhookService gitHubWebhookService;
    @Autowired
    @Qualifier("reviewRedisTemplate")
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void clearRedisQueues() {
        redisTemplate.delete(List.of(
                "codepilot:review-jobs",
                "codepilot:review-jobs:processing",
                "codepilot:review-jobs:retry",
                "codepilot:review-jobs:dead-letter"));
    }

    @Test
    void concurrentWorkersClaimJobAtomicallyOnlyOnce() throws Exception {
        ReviewJob job = createPersistedJob("concurrent");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Boolean> claimTask = () -> reviewJobService.claim(job.getId());
            List<Future<Boolean>> results = pool.invokeAll(List.of(claimTask, claimTask, claimTask, claimTask));
            long winners = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).count();

            assertThat(winners).isEqualTo(1);
            ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo("PROCESSING");
            assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateReviewSubmissionForSameHeadShaIsIdempotent() {
        User user = new User();
        user.setUsername("dup-user-" + UUID.randomUUID());
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        user.setEmail(email);
        user = userRepository.save(user);

        DefaultOAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("login", user.getUsername(), "email", email),
                "login");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_USER"));
        SecurityContextHolder.setContext(context);

        try {
            Repository repo = new Repository();
            repo.setOwner(user);
            repo.setName("dup-repo");
            repo.setFullName("dup-org/repo-" + UUID.randomUUID());
            repo.setGithubRepoId("dup-gh-" + UUID.randomUUID());
            repo = repositoryRepository.save(repo);

            PullRequest pr = new PullRequest();
            pr.setRepository(repo);
            pr.setPrNumber(42);
            pr.setTitle("Dup PR");
            pr.setAuthor(user.getUsername());
            pr.setStatus("open");
            pr.setHeadSha("abc123def456");
            pr = pullRequestRepository.save(pr);

            CreateReviewRequest request = new CreateReviewRequest();
            request.setPullRequestId(pr.getId());

            ReflectionTestUtils.setField(reviewService, "asyncEnabled", true);
            try {
                ReviewDto first = reviewService.createReview(repo.getId(), request);
                ReviewDto second = reviewService.createReview(repo.getId(), request);
                assertThat(second.getId()).isEqualTo(first.getId());
            } finally {
                ReflectionTestUtils.setField(reviewService, "asyncEnabled", false);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void staleProcessingJobIsRecoveredAndRequeuedFromProcessingQueue() {
        ReviewJob job = createPersistedJob("stale");
        reviewJobProducer.publish(job);
        assertThat(reviewJobService.claim(job.getId())).isTrue();

        ReviewJob stale = reviewJobRepository.findById(job.getId()).orElseThrow();
        stale.setProcessingHeartbeatAt(ZonedDateTime.now().minusMinutes(25));
        reviewJobRepository.save(stale);

        String payload = redisTemplate.opsForList().rightPopAndLeftPush(
                "codepilot:review-jobs", "codepilot:review-jobs:processing");
        assertThat(payload).contains(job.getId().toString());

        ReflectionTestUtils.setField(reviewJobWorker, "enabled", true);
        try {
            reviewJobWorker.recoverStaleJobs();
        } finally {
            ReflectionTestUtils.setField(reviewJobWorker, "enabled", false);
        }

        ReviewJob recovered = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("RETRYING");
        assertThat(recovered.getLastErrorCategory()).isEqualTo("TIMEOUT");

        String requeued = redisTemplate.opsForList().rightPop("codepilot:review-jobs");
        assertThat(requeued).contains(job.getId().toString());
    }

    @Test
    void pendingAndStaleQueuedJobsAreRecoveredOnRestart() {
        ReviewJob pendingJob = createPersistedJob("pending-restart");
        ReviewJob queuedJob = createPersistedJob("queued-restart");
        reviewJobService.markQueued(queuedJob);

        ReflectionTestUtils.setField(reviewJobProducer, "enabled", true);
        ReflectionTestUtils.setField(reviewJobProducer, "queuedRecoverySeconds", 0L);
        try {
            reviewJobProducer.recoverPendingJobs();
        } finally {
            ReflectionTestUtils.setField(reviewJobProducer, "enabled", false);
            ReflectionTestUtils.setField(reviewJobProducer, "queuedRecoverySeconds", 60L);
        }

        assertThat(reviewJobRepository.findById(pendingJob.getId()).orElseThrow().getStatus())
                .isEqualTo("QUEUED");
        assertThat(redisTemplate.opsForList().size("codepilot:review-jobs")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void duplicateWebhookDeliveryAndFailedDeliveryRecoveryWork() {
        String deliveryId = "dup-deliv-" + UUID.randomUUID();
        byte[] validPayload = "{\"action\":\"opened\",\"repository\":{\"id\":12345},\"number\":7}"
                .getBytes(StandardCharsets.UTF_8);

        WebhookDelivery first = gitHubWebhookService.receive(deliveryId, "pull_request", validPayload);
        WebhookDelivery duplicate = gitHubWebhookService.receive(deliveryId, "pull_request", validPayload);
        assertThat(duplicate.getId()).isEqualTo(first.getId());

        String badDelivId = "fail-deliv-" + UUID.randomUUID();
        byte[] missingPrPayload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        gitHubWebhookService.receive(badDelivId, "pull_request", missingPrPayload);

        gitHubWebhookService.processPendingEvents();

        WebhookDelivery failed = webhookDeliveryRepository.findByDeliveryId(badDelivId).orElseThrow();
        assertThat(failed.getProcessingStatus()).isEqualTo("FAILED");
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isNotNull();
    }

    private ReviewJob createPersistedJob(String prefix) {
        User user = new User();
        user.setUsername(prefix + "-user-" + UUID.randomUUID());
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user = userRepository.save(user);

        Repository repo = new Repository();
        repo.setOwner(user);
        repo.setName(prefix + "-repo");
        repo.setFullName(prefix + "-org/repo-" + UUID.randomUUID());
        repo.setGithubRepoId(prefix + "-gh-" + UUID.randomUUID());
        repo = repositoryRepository.save(repo);

        PullRequest pr = new PullRequest();
        pr.setRepository(repo);
        pr.setPrNumber(1);
        pr.setTitle(prefix + " PR");
        pr.setAuthor(user.getUsername());
        pr.setStatus("open");
        pr = pullRequestRepository.save(pr);

        Review review = new Review();
        review.setPullRequest(pr);
        review.setStatus("QUEUED");
        review = reviewRepository.save(review);

        return reviewJobService.createJob(review);
    }
}

