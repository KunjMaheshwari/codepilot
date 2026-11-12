package com.codepilot.integration;

import com.codepilot.ai.GeminiCodeReviewProvider;
import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.GitHubCommentPublication;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.GitHubCommentPublicationRepository;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewJobRepository;
import com.codepilot.repository.ReviewRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.EncryptionService;
import com.codepilot.service.GitHubApiClient;
import com.codepilot.service.ReviewJobService;
import com.codepilot.service.ReviewJobWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FullReviewJourneyIntegrationTest {

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
        registry.add("codepilot.review.async.enabled", () -> "true");
        registry.add("codepilot.review.publish-comments-enabled", () -> "true");
        registry.add("gemini.api.key", () -> "test-gemini-key");
        registry.add("gemini.api.url", () -> "https://gemini.integration.test");
        registry.add("gemini.api.model", () -> "gemini-2.5-flash");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private ReviewJobRepository reviewJobRepository;

    @Autowired
    private GitHubCommentPublicationRepository publicationRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private ReviewJobWorker reviewJobWorker;

    @Autowired
    private ReviewJobService reviewJobService;

    @Autowired
    private GeminiCodeReviewProvider geminiCodeReviewProvider;

    @MockBean
    private GitHubApiClient gitHubApiClient;

    private MockRestServiceServer geminiServer;
    private User owner;
    private User otherTenant;

    @BeforeEach
    void setUp() {
        publicationRepository.deleteAll();
        findingRepository.deleteAll();
        reviewJobRepository.deleteAll();
        reviewRepository.deleteAll();
        pullRequestRepository.deleteAll();
        repositoryRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        RestTemplate geminiRestTemplate = (RestTemplate) ReflectionTestUtils.getField(
                geminiCodeReviewProvider, "restTemplate");
        geminiServer = MockRestServiceServer.createServer(geminiRestTemplate);

        owner = new User();
        owner.setUsername("journey-owner-" + UUID.randomUUID());
        owner.setEmail("journey-owner-" + UUID.randomUUID() + "@example.com");
        owner = userRepository.save(owner);

        ConnectedGitHubAccount ownerAccount = new ConnectedGitHubAccount();
        ownerAccount.setUser(owner);
        ownerAccount.setGithubUserId("40001");
        ownerAccount.setGithubUsername(owner.getUsername());
        ownerAccount.setAccessToken(encryptionService.encrypt("gho_journey_secret_token"));
        accountRepository.save(ownerAccount);

        otherTenant = new User();
        otherTenant.setUsername("journey-other-" + UUID.randomUUID());
        otherTenant.setEmail("journey-other-" + UUID.randomUUID() + "@example.com");
        otherTenant = userRepository.save(otherTenant);
    }

    @Test
    void fullEndToEndReviewJourneyAcrossHttpRedisPostgresGeminiAndGitHub() throws Exception {
        when(gitHubApiClient.getUserRepositories("gho_journey_secret_token", 1, 100))
                .thenReturn(List.of(Map.of(
                        "id", 77701,
                        "name", "payments",
                        "full_name", "acme/payments",
                        "private", true,
                        "archived", false,
                        "default_branch", "main",
                        "html_url", "https://github.com/acme/payments")));

        MvcResult syncRepoResult = mockMvc.perform(post("/api/v1/repositories/sync")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 40001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("acme/payments"))
                .andReturn();

        assertThat(syncRepoResult.getResponse().getContentAsString())
                .doesNotContain("gho_journey_secret_token");

        Repository repo = repositoryRepository.findByGithubRepoIdAndOwnerId("77701", owner.getId()).orElseThrow();

        when(gitHubApiClient.getPullRequests("gho_journey_secret_token", "acme", "payments", 1, 100))
                .thenReturn(List.of(Map.of(
                        "number", 55,
                        "title", "Validate webhook token",
                        "state", "open",
                        "user", Map.of("login", owner.getUsername()),
                        "base", Map.of("ref", "main", "sha", "base-55"),
                        "head", Map.of("ref", "fix/webhook", "sha", "head-55"))));

        mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/pull-requests/sync")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 40001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].prNumber").value(55));

        PullRequest pr = pullRequestRepository.findByRepositoryIdAndPrNumber(repo.getId(), 55).orElseThrow();

        MvcResult reviewRes = mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/reviews")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 40001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pullRequestId\":\"" + pr.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        JsonNode createdReview = objectMapper.readTree(reviewRes.getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(createdReview.get("id").asText());

        String diff = """
                diff --git a/src/Webhook.java b/src/Webhook.java
                --- a/src/Webhook.java
                +++ b/src/Webhook.java
                @@ -1,2 +1,3 @@
                 public class Webhook {
                +    boolean ok = a.equals(b);
                 }
                """;
        when(gitHubApiClient.getPullRequestDiff("gho_journey_secret_token", "acme", "payments", 55))
                .thenReturn(diff);
        when(gitHubApiClient.createPullRequestReviewComment(
                eq("gho_journey_secret_token"), eq("acme"), eq("payments"), eq(55),
                anyString(), eq("head-55"), eq("src/Webhook.java"), eq(2)))
                .thenReturn(88888L);

        String geminiResponse = """
                {"candidates":[{"content":{"parts":[{"text":"[{\\"title\\":\\"Non-constant-time comparison\\",\\"description\\":\\"Use MessageDigest.isEqual for signature comparison\\",\\"category\\":\\"SECURITY\\",\\"severity\\":\\"HIGH\\",\\"filePath\\":\\"src/Webhook.java\\",\\"lineNumber\\":2,\\"evidence\\":\\"a.equals(b)\\",\\"impact\\":\\"Timing side-channel\\",\\"suggestedFix\\":\\"MessageDigest.isEqual(a, b)\\",\\"confidence\\":\\"HIGH\\"}]"}]}}]}
                """;
        geminiServer.expect(requestTo("https://gemini.integration.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiResponse, MediaType.APPLICATION_JSON));

        // Process the queued Redis job synchronously via worker.poll()
        reviewJobWorker.poll();
        geminiServer.verify();

        Review completed = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");

        List<GitHubCommentPublication> pubs = publicationRepository.findByReviewId(reviewId);
        assertThat(pubs).singleElement().satisfies(pub -> {
            assertThat(pub.getStatus()).isEqualTo("PUBLISHED");
            assertThat(pub.getGithubCommentId()).isEqualTo(88888L);
        });

        mockMvc.perform(get("/api/v1/reviews/" + reviewId + "/findings")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 40001);
                            attrs.put("login", owner.getUsername());
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].filePath").value("src/Webhook.java"))
                .andExpect(jsonPath("$.content[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.content[0].message").value("Use MessageDigest.isEqual for signature comparison"));

        // Cross-tenant isolation: another user cannot access the review or findings
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 40002);
                            attrs.put("login", otherTenant.getUsername());
                        })))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/reviews/" + reviewId + "/findings")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 40002);
                            attrs.put("login", otherTenant.getUsername());
                        })))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentWorkersClaimReviewJobAtMostOnce() throws Exception {
        Repository repo = new Repository();
        repo.setOwner(owner);
        repo.setGithubRepoId("repo-conc-" + UUID.randomUUID());
        repo.setName("conc");
        repo.setFullName("acme/conc");
        repo = repositoryRepository.save(repo);

        PullRequest pr = new PullRequest();
        pr.setRepository(repo);
        pr.setPrNumber(9);
        pr.setTitle("Concurrency test");
        pr.setAuthor(owner.getUsername());
        pr.setStatus("open");
        pr = pullRequestRepository.save(pr);

        Review review = new Review();
        review.setPullRequest(pr);
        review.setStatus("QUEUED");
        review = reviewRepository.save(review);

        ReviewJob job = reviewJobService.createJob(review);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        UUID jobId = job.getId();

        Future<Boolean> f1 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return reviewJobService.claim(jobId);
        });
        Future<Boolean> f2 = pool.submit(() -> {
            ready.countDown();
            go.await();
            return reviewJobService.claim(jobId);
        });

        ready.await();
        go.countDown();
        boolean c1 = f1.get();
        boolean c2 = f2.get();
        pool.shutdown();

        assertThat(List.of(c1, c2)).containsExactlyInAnyOrder(true, false);
    }
}

