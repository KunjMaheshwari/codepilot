package com.codepilot.ai;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.ReviewDto;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.User;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.GitHubIntegrationService;
import com.codepilot.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@ActiveProfiles("test")
class AiReviewEngineIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private GeminiCodeReviewProvider geminiProvider;

    @MockBean
    private GitHubIntegrationService gitHubIntegrationService;

    private MockRestServiceServer mockServer;

    private User testUser;
    private Repository testRepo;
    private PullRequest testPr;

    @BeforeEach
    void setUp() {
        reviewFindingRepository.deleteAll();
        reviewRepository.deleteAll();
        pullRequestRepository.deleteAll();
        repositoryRepository.deleteAll();
        userRepository.deleteAll();

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(geminiProvider, "restTemplate");
        mockServer = MockRestServiceServer.createServer(restTemplate);

        ReflectionTestUtils.setField(geminiProvider, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(geminiProvider, "apiUrl", "http://mock");

        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@example.com");
        testUser = userRepository.save(testUser);

        OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(),
                Map.of("email", "testuser@example.com", "login", "testuser"),
                "login"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(oauth2User, null, oauth2User.getAuthorities())
        );

        testRepo = new Repository();
        testRepo.setOwner(testUser);
        testRepo.setName("test-repo");
        testRepo.setFullName("testuser/test-repo");
        testRepo.setGithubRepoId("repo-1");
        testRepo = repositoryRepository.save(testRepo);

        testPr = new PullRequest();
        testPr.setRepository(testRepo);
        testPr.setPrNumber(1);
        testPr.setTitle("Test PR");
        testPr.setAuthor("testuser");
        testPr.setStatus("open");
        testPr = pullRequestRepository.save(testPr);
    }

    @Test
    void executeFullReview_ShouldPersistValidFindings() {
        String diff = """
            diff --git a/src/Test.java b/src/Test.java
            index 123..456 100644
            --- a/src/Test.java
            +++ b/src/Test.java
            @@ -1,2 +1,3 @@
             public class Test {
            +    int x; // bad variable name
             }
            """;
        when(gitHubIntegrationService.getPullRequestDiff(any(), any(), eq(1))).thenReturn(diff);

        String geminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "[{\\"title\\": \\"Bad variable\\", \\"description\\": \\"Use better name\\", \\"category\\": \\"CODE_QUALITY\\", \\"severity\\": \\"LOW\\", \\"filePath\\": \\"src/Test.java\\", \\"lineNumber\\": 2, \\"evidence\\": \\"int x;\\"}]"
                      }
                    ]
                  }
                }
              ]
            }
            """;

        mockServer.expect(ExpectedCount.once(), requestTo("http://mock/v1beta/models/mock:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiResponse, MediaType.APPLICATION_JSON));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setPullRequestId(testPr.getId());

        ReviewDto reviewDto = reviewService.createReview(testRepo.getId(), request);

        mockServer.verify();

        Review savedReview = reviewRepository.findById(reviewDto.getId()).orElseThrow();
        assertThat(savedReview.getStatus()).isEqualTo("COMPLETED");

        var findings = reviewFindingRepository.findByReviewId(savedReview.getId(), org.springframework.data.domain.Pageable.unpaged());
        assertThat(findings.getContent()).hasSize(1);
        assertThat(findings.getContent().get(0).getTitle()).isEqualTo("Bad variable");
        assertThat(findings.getContent().get(0).getLineNumber()).isEqualTo(2);
    }

    @Test
    void executeReview_ShouldHandleRateLimitRetries() {
         String diff = """
            diff --git a/src/Test.java b/src/Test.java
            index 123..456 100644
            --- a/src/Test.java
            +++ b/src/Test.java
            @@ -1,2 +1,3 @@
             public class Test {
            +    int y;
             }
            """;
        when(gitHubIntegrationService.getPullRequestDiff(any(), any(), eq(1))).thenReturn(diff);

        String geminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "[]"
                      }
                    ]
                  }
                }
              ]
            }
            """;

        mockServer.expect(ExpectedCount.once(), requestTo("http://mock/v1beta/models/mock:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        mockServer.expect(ExpectedCount.once(), requestTo("http://mock/v1beta/models/mock:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiResponse, MediaType.APPLICATION_JSON));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setPullRequestId(testPr.getId());

        ReviewDto reviewDto = reviewService.createReview(testRepo.getId(), request);

        mockServer.verify();
        Review savedReview = reviewRepository.findById(reviewDto.getId()).orElseThrow();
        assertThat(savedReview.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void executeReview_ShouldMarkFailedOnPermanentError() {
         String diff = """
            diff --git a/src/Test.java b/src/Test.java
            index 123..456 100644
            --- a/src/Test.java
            +++ b/src/Test.java
            @@ -1,2 +1,3 @@
             public class Test {
            +    int z;
             }
            """;
        when(gitHubIntegrationService.getPullRequestDiff(any(), any(), eq(1))).thenReturn(diff);

        mockServer.expect(ExpectedCount.once(), requestTo("http://mock/v1beta/models/mock:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setPullRequestId(testPr.getId());

        ReviewDto reviewDto = reviewService.createReview(testRepo.getId(), request);

        mockServer.verify();
        Review savedReview = reviewRepository.findById(reviewDto.getId()).orElseThrow();
        assertThat(savedReview.getStatus()).isEqualTo("FAILED");
    }
}
