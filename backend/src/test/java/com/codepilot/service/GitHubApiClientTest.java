package com.codepilot.service;

import com.codepilot.exception.GitHubApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.GET;

class GitHubApiClientTest {

    private MockRestServiceServer server;
    private GitHubApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new GitHubApiClient(restTemplate);
        Field apiUrl = GitHubApiClient.class.getDeclaredField("githubApiUrl");
        apiUrl.setAccessible(true);
        apiUrl.set(client, "https://api.github.test");
    }

    @Test
    void sendsGitHubHeadersAndParsesRepositories() {
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andExpect(method(GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-token"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("[{\"id\":1,\"name\":\"repo\"}]", org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.getUserRepositories("secret-token", 1, 10)).hasSize(1);
        server.verify();
    }

    @Test
    void doesNotRetryUnauthorizedResponses() {
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getUserRepositories("token", 1, 10))
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("GitHub authentication failed");
        server.verify();
    }

    @Test
    void classifiesForbiddenWithoutRetrying() {
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.getUserRepositories("token", 1, 10))
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("GitHub denied access to this resource");
        server.verify();
    }

    @Test
    void classifiesNotFoundWithoutRetrying() {
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getUserRepositories("token", 1, 10))
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("GitHub resource was not found or is inaccessible");
        server.verify();
    }

    @Test
    void retriesRateLimitWhenRetryAfterAllowsImmediateRetry() {
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0"));
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0"));
        server.expect(requestTo("https://api.github.test/user/repos?page=1&per_page=10"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0"));

        assertThatThrownBy(() -> client.getUserRepositories("token", 1, 10))
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("GitHub API rate limit exceeded");
        server.verify();
    }

    @Test
    void rejectsMissingAccessTokenBeforeMakingRequest() {
        assertThatThrownBy(() -> client.getUserRepositories(" ", 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GitHub access token is missing");
    }

    @Test
    void rejectsEmptyDiffs() {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/1"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("", org.springframework.http.MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.getPullRequestDiff("token", "owner", "repo", 1))
                .isInstanceOf(GitHubApiException.class)
                .hasMessage("GitHub returned an empty pull request diff");
        server.verify();
    }
}
