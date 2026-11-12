package com.codepilot.service;

import com.codepilot.exception.GitHubApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@Slf4j
public class GitHubApiClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_WAIT_SECONDS = 5;

    private final RestTemplate restTemplate;

    @Value("${github.api.url:https://api.github.com}")
    private String githubApiUrl;

    @Autowired
    public GitHubApiClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    GitHubApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private HttpHeaders createHeaders(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("GitHub access token is missing");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    public List<Map<String, Object>> getUserRepositories(String accessToken, int page, int perPage) {
        String url = String.format("%s/user/repos?page=%d&per_page=%d", githubApiUrl, page, perPage);
        return executeWithRetry(() -> exchangeList(url, accessToken));
    }

    public Map<String, Object> getPullRequest(String accessToken, String owner, String repo, int prNumber) {
        String url = String.format("%s/repos/%s/%s/pulls/%d", githubApiUrl, owner, repo, prNumber);
        return executeWithRetry(() -> {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(createHeaders(accessToken)),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            return response.getBody();
        });
    }

    public List<Map<String, Object>> getPullRequests(
            String accessToken, String owner, String repo, int page, int perPage) {
        String url = String.format("%s/repos/%s/%s/pulls?state=all&page=%d&per_page=%d",
                githubApiUrl, owner, repo, page, perPage);
        return executeWithRetry(() -> exchangeList(url, accessToken));
    }

    public String getPullRequestDiff(String accessToken, String owner, String repo, int prNumber) {
        String url = String.format("%s/repos/%s/%s/pulls/%d", githubApiUrl, owner, repo, prNumber);
        return executeWithRetry(() -> {
            HttpHeaders headers = createHeaders(accessToken);
            headers.set(HttpHeaders.ACCEPT, "application/vnd.github.diff");
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getBody() == null || response.getBody().isBlank()) {
                throw new GitHubApiException("GitHub returned an empty pull request diff",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return response.getBody();
        });
    }

    private List<Map<String, Object>> exchangeList(String url, String accessToken) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                URI.create(url), HttpMethod.GET, new HttpEntity<>(createHeaders(accessToken)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        return response.getBody() == null ? List.of() : response.getBody();
    }

    private <T> T executeWithRetry(Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (HttpStatusCodeException exception) {
                HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
                if (status == null) {
                    throw new GitHubApiException("GitHub returned an unsupported HTTP error",
                            HttpStatus.BAD_GATEWAY);
                }
                Instant retryAt = retryAt(exception);
                if (!isRetryable(exception, retryAt) || attempt == MAX_ATTEMPTS) {
                    throw classify(exception, status, retryAt);
                }
                waitBeforeRetry(attempt, retryAt);
            } catch (ResourceAccessException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("GitHub API request failed after {} attempts: {}", attempt,
                            exception.getClass().getSimpleName());
                    throw new GitHubApiException("GitHub API is unavailable", HttpStatus.GATEWAY_TIMEOUT);
                }
                waitBeforeRetry(attempt, null);
            }
        }
        throw new GitHubApiException("GitHub API request failed", HttpStatus.BAD_GATEWAY);
    }

    private boolean isRetryable(HttpStatusCodeException exception, Instant retryAt) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return status == HttpStatus.TOO_MANY_REQUESTS
                || status == HttpStatus.BAD_GATEWAY
                || status == HttpStatus.SERVICE_UNAVAILABLE
                || status == HttpStatus.GATEWAY_TIMEOUT
                || (status == HttpStatus.FORBIDDEN && retryAt != null);
    }

    private GitHubApiException classify(HttpStatusCodeException exception, HttpStatus status, Instant retryAt) {
        String message = switch (status) {
            case UNAUTHORIZED -> "GitHub authentication failed";
            case FORBIDDEN -> "GitHub denied access to this resource";
            case NOT_FOUND -> "GitHub resource was not found or is inaccessible";
            case TOO_MANY_REQUESTS -> "GitHub API rate limit exceeded";
            default -> status.is5xxServerError() ? "GitHub API is unavailable" : "GitHub API request failed";
        };
        return new GitHubApiException(message, status, retryAt);
    }

    private Instant retryAt(HttpStatusCodeException exception) {
        String retryAfter = exception.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                return Instant.now().plusSeconds(Math.max(0, Long.parseLong(retryAfter)));
            } catch (NumberFormatException ignored) {
                log.debug("GitHub returned a non-numeric Retry-After header");
            }
        }
        String reset = exception.getResponseHeaders().getFirst("X-RateLimit-Reset");
        if (reset != null) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(reset));
            } catch (NumberFormatException ignored) {
                log.debug("GitHub returned an invalid X-RateLimit-Reset header");
            }
        }
        return null;
    }

    private void waitBeforeRetry(int attempt, Instant retryAt) {
        long waitSeconds = retryAt == null
                ? attempt
                : Math.max(0, retryAt.getEpochSecond() - Instant.now().getEpochSecond());
        if (waitSeconds > MAX_RETRY_WAIT_SECONDS) {
            throw new GitHubApiException("GitHub requested a retry after the client limit",
                    HttpStatus.TOO_MANY_REQUESTS, retryAt);
        }
        try {
            Thread.sleep(waitSeconds * 1000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("GitHub API retry interrupted", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
