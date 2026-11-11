package com.codepilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GitHubApiClient {

    private final RestTemplate restTemplate;

    @Value("${github.api.url:https://api.github.com}")
    private String githubApiUrl;

    public GitHubApiClient() {
        this.restTemplate = new RestTemplate();
        // Here we could configure timeouts, interceptors for rate limiting, etc.
    }

    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("Accept", "application/vnd.github.v3+json");
        return headers;
    }

    public List<Map<String, Object>> getUserRepositories(String accessToken, int page, int perPage) {
        String url = String.format("%s/user/repos?page=%d&per_page=%d", githubApiUrl, page, perPage);
        return executeWithRetry(() -> {
            HttpEntity<String> entity = new HttpEntity<>(createHeaders(accessToken));
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            return response.getBody();
        });
    }

    public Map<String, Object> getPullRequest(String accessToken, String owner, String repo, int prNumber) {
        String url = String.format("%s/repos/%s/%s/pulls/%d", githubApiUrl, owner, repo, prNumber);
        return executeWithRetry(() -> {
            HttpEntity<String> entity = new HttpEntity<>(createHeaders(accessToken));
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        });
    }

    public List<Map<String, Object>> getPullRequests(
            String accessToken, String owner, String repo, int page, int perPage) {
        String url = String.format("%s/repos/%s/%s/pulls?state=all&page=%d&per_page=%d",
                githubApiUrl, owner, repo, page, perPage);
        return executeWithRetry(() -> {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createHeaders(accessToken)),
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            return response.getBody();
        });
    }

    public String getPullRequestDiff(String accessToken, String owner, String repo, int prNumber) {
        String url = String.format("%s/repos/%s/%s/pulls/%d", githubApiUrl, owner, repo, prNumber);
        return executeWithRetry(() -> {
            HttpHeaders headers = createHeaders(accessToken);
            headers.set("Accept", "application/vnd.github.v3.diff");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class
            );
            return response.getBody();
        });
    }

    private <T> T executeWithRetry(java.util.function.Supplier<T> action) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return action.get();
            } catch (HttpClientErrorException.Forbidden e) {
                if (e.getMessage() != null && e.getMessage().contains("rate limit")) {
                    log.warn("GitHub API rate limit exceeded. Retrying...");
                    try {
                        Thread.sleep(1000 * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (i == maxRetries - 1) throw e;
                } else {
                    throw e;
                }
            } catch (HttpClientErrorException e) {
                 log.error("Error calling GitHub API: {}", e.getMessage());
                 throw new RuntimeException("Failed to call GitHub API", e);
            } catch (Exception e) {
                 if (i == maxRetries - 1) throw new RuntimeException("Failed after retries", e);
                 try {
                     Thread.sleep(1000 * (i + 1));
                 } catch (InterruptedException ie) {
                     Thread.currentThread().interrupt();
                 }
            }
        }
        throw new RuntimeException("Failed to call GitHub API");
    }
}
