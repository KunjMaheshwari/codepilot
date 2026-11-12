package com.codepilot.integration;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewJobRepository;
import com.codepilot.repository.ReviewRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.EncryptionService;
import com.codepilot.service.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ManualReviewWorkflowIntegrationTest {

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
        registry.add("codepilot.review.worker.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

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
    private ReviewJobRepository reviewJobRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GitHubApiClient gitHubApiClient;

    private User owner;
    private User otherUser;

    @BeforeEach
    void setUp() {
        reviewJobRepository.deleteAll();
        reviewRepository.deleteAll();
        pullRequestRepository.deleteAll();
        repositoryRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        owner = new User();
        owner.setUsername("workflow-owner-" + UUID.randomUUID());
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner = userRepository.save(owner);

        ConnectedGitHubAccount ownerAccount = new ConnectedGitHubAccount();
        ownerAccount.setUser(owner);
        ownerAccount.setGithubUserId("20001");
        ownerAccount.setGithubUsername(owner.getUsername());
        ownerAccount.setAccessToken(encryptionService.encrypt("gho_owner_token"));
        accountRepository.save(ownerAccount);

        otherUser = new User();
        otherUser.setUsername("workflow-other-" + UUID.randomUUID());
        otherUser.setEmail("other-" + UUID.randomUUID() + "@example.com");
        otherUser = userRepository.save(otherUser);

        ConnectedGitHubAccount otherAccount = new ConnectedGitHubAccount();
        otherAccount.setUser(otherUser);
        otherAccount.setGithubUserId("20002");
        otherAccount.setGithubUsername(otherUser.getUsername());
        otherAccount.setAccessToken(encryptionService.encrypt("gho_other_token"));
        accountRepository.save(otherAccount);
    }

    @Test
    void syncRepositoriesAndPullRequestsAndCreateManualReviewEndToEnd() throws Exception {
        when(gitHubApiClient.getUserRepositories("gho_owner_token", 1, 100))
                .thenReturn(List.of(Map.of(
                        "id", 9001,
                        "name", "codepilot-demo",
                        "full_name", "owner/codepilot-demo",
                        "private", false,
                        "archived", false,
                        "default_branch", "main",
                        "html_url", "https://github.com/owner/codepilot-demo")));

        mockMvc.perform(post("/api/v1/repositories/sync")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 20001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("owner/codepilot-demo"))
                .andExpect(jsonPath("$.content[0].isActive").value(true));

        Repository repo = repositoryRepository.findByGithubRepoIdAndOwnerId("9001", owner.getId()).orElseThrow();

        when(gitHubApiClient.getPullRequests("gho_owner_token", "owner", "codepilot-demo", 1, 100))
                .thenReturn(List.of(
                        Map.of(
                                "number", 101,
                                "title", "Add rate limiter",
                                "state", "open",
                                "user", Map.of("login", "dev1"),
                                "base", Map.of("ref", "main", "sha", "base1"),
                                "head", Map.of("ref", "feat/rate-limit", "sha", "head1")),
                        Map.of(
                                "number", 102,
                                "title", "Already merged",
                                "state", "closed",
                                "merged_at", "2026-01-01T00:00:00Z",
                                "user", Map.of("login", "dev2"),
                                "base", Map.of("ref", "main", "sha", "base2"),
                                "head", Map.of("ref", "feat/old", "sha", "head2"))));

        mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/pull-requests/sync")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 20001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Cross-tenant PR sync must return 404
        mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/pull-requests/sync")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 20002);
                            attrs.put("login", otherUser.getUsername());
                        }))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        PullRequest openPr = pullRequestRepository.findByRepositoryIdAndPrNumber(repo.getId(), 101).orElseThrow();
        PullRequest mergedPr = pullRequestRepository.findByRepositoryIdAndPrNumber(repo.getId(), 102).orElseThrow();
        assertThat(mergedPr.getStatus()).isEqualTo("merged");

        // Triggering manual review for open PR queues a review & job
        MvcResult createResult = mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/reviews")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 20001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pullRequestId\":\"" + openPr.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn();

        // Duplicate trigger for the same open PR + headSha returns the same review and does not create a second job
        JsonNode firstReview = objectMapper.readTree(createResult.getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/reviews")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 20001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pullRequestId\":\"" + openPr.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(firstReview.get("id").asText()))
                .andExpect(jsonPath("$.jobId").value(firstReview.get("jobId").asText()))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        assertThat(reviewRepository.count()).isEqualTo(1);
        assertThat(reviewJobRepository.count()).isEqualTo(1);

        // Merged PR cannot be manually reviewed
        mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/reviews")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 20001);
                            attrs.put("login", owner.getUsername());
                        }))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pullRequestId\":\"" + mergedPr.getId() + "\"}"))
                .andExpect(status().isBadRequest());
    }
}

