package com.codepilot.infrastructure;

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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresRedisInfrastructureIntegrationTest {

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
    private Flyway flyway;

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
    private ReviewJobProducer reviewJobProducer;

    @Autowired
    private ReviewJobService reviewJobService;

    @Autowired
    private GitHubWebhookService gitHubWebhookService;

    @Autowired
    @Qualifier("reviewRedisTemplate")
    private RedisTemplate<String, String> redisTemplate;

    @Test
    void flywayMigrationsAppliedCleanlyOnPostgres17() {
        assertThat(flyway.info().applied()).hasSize(6);
    }

    @Test
    void pendingJobRecoveryAndRedisQueueRoundTrip() {
        User user = new User();
        user.setUsername("pg-user-" + UUID.randomUUID());
        user.setEmail("pg-" + UUID.randomUUID() + "@example.com");
        user = userRepository.save(user);

        Repository repo = new Repository();
        repo.setOwner(user);
        repo.setName("demo");
        repo.setFullName("pg-org/demo-" + UUID.randomUUID());
        repo.setGithubRepoId("gh-" + UUID.randomUUID());
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);

        PullRequest pr = new PullRequest();
        pr.setRepository(repo);
        pr.setPrNumber(1);
        pr.setTitle("Test PR");
        pr.setAuthor("pg-user");
        pr.setStatus("open");
        pr = pullRequestRepository.save(pr);

        Review review = new Review();
        review.setPullRequest(pr);
        review.setStatus("QUEUED");
        review = reviewRepository.save(review);

        ReviewJob job = reviewJobService.createJob(review);
        assertThat(job.getStatus()).isEqualTo("PENDING");

        reviewJobProducer.publish(job);

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("QUEUED");

        String queuedPayload = redisTemplate.opsForList().rightPop("codepilot:review-jobs");
        assertThat(queuedPayload).contains(job.getId().toString());
        assertThat(queuedPayload).contains(repo.getId().toString());
    }

    @Test
    void scopedHeartbeatOnlyTouchesSpecifiedProcessingJobs() {
        User user = new User();
        user.setUsername("hb-user-" + UUID.randomUUID());
        user.setEmail("hb-" + UUID.randomUUID() + "@example.com");
        user = userRepository.save(user);

        Repository repo = new Repository();
        repo.setOwner(user);
        repo.setName("hb-repo");
        repo.setFullName("hb-org/repo-" + UUID.randomUUID());
        repo.setGithubRepoId("hb-gh-" + UUID.randomUUID());
        repo = repositoryRepository.save(repo);

        PullRequest pr1 = new PullRequest();
        pr1.setRepository(repo);
        pr1.setPrNumber(10);
        pr1.setTitle("PR 10");
        pr1.setAuthor("hb-user");
        pr1.setStatus("open");
        pr1 = pullRequestRepository.save(pr1);

        PullRequest pr2 = new PullRequest();
        pr2.setRepository(repo);
        pr2.setPrNumber(11);
        pr2.setTitle("PR 11");
        pr2.setAuthor("hb-user");
        pr2.setStatus("open");
        pr2 = pullRequestRepository.save(pr2);

        Review r1 = new Review();
        r1.setPullRequest(pr1);
        r1.setStatus("PROCESSING");
        r1 = reviewRepository.save(r1);

        Review r2 = new Review();
        r2.setPullRequest(pr2);
        r2.setStatus("PROCESSING");
        r2 = reviewRepository.save(r2);

        ZonedDateTime oldTimestamp = ZonedDateTime.now().minusMinutes(30);

        ReviewJob job1 = new ReviewJob();
        job1.setReview(r1);
        job1.setStatus("PROCESSING");
        job1.setAttemptCount(1);
        job1.setStartedAt(oldTimestamp);
        job1.setProcessingHeartbeatAt(oldTimestamp);
        job1 = reviewJobRepository.save(job1);

        ReviewJob job2 = new ReviewJob();
        job2.setReview(r2);
        job2.setStatus("PROCESSING");
        job2.setAttemptCount(1);
        job2.setStartedAt(oldTimestamp);
        job2.setProcessingHeartbeatAt(oldTimestamp);
        job2 = reviewJobRepository.save(job2);

        int updated = reviewJobService.touchProcessingJobs(List.of(job1.getId()));
        assertThat(updated).isEqualTo(1);

        ReviewJob reloaded1 = reviewJobRepository.findById(job1.getId()).orElseThrow();
        ReviewJob reloaded2 = reviewJobRepository.findById(job2.getId()).orElseThrow();
        assertThat(reloaded1.getProcessingHeartbeatAt()).isAfter(oldTimestamp.plusMinutes(1));
        assertThat(reloaded2.getProcessingHeartbeatAt()).isBefore(oldTimestamp.plusSeconds(5));
    }

    @Test
    void webhookDeliveryClaimAndProcessExecutesInTransaction() {
        String deliveryId = "deliv-" + UUID.randomUUID();
        byte[] payload = "{\"action\":\"opened\",\"repository\":{\"id\":999},\"number\":1}"
                .getBytes(StandardCharsets.UTF_8);

        WebhookDelivery delivery = gitHubWebhookService.receive(deliveryId, "ping", payload);
        assertThat(delivery.getProcessingStatus()).isEqualTo("RECEIVED");

        gitHubWebhookService.processPendingEvents();

        WebhookDelivery processed = webhookDeliveryRepository.findByDeliveryId(deliveryId).orElseThrow();
        assertThat(processed.getProcessingStatus()).isEqualTo("IGNORED");
        assertThat(processed.getAttemptCount()).isEqualTo(1);
        assertThat(processed.getErrorCategory()).isEqualTo("UNSUPPORTED_EVENT");
    }
}

