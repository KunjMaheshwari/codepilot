package com.codepilot.controller;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.PageDto;
import com.codepilot.dto.RepositoryDto;
import com.codepilot.dto.ReviewDto;
import com.codepilot.service.RepositoryService;
import com.codepilot.service.ReviewService;
import com.codepilot.service.GitHubIntegrationService;
import com.codepilot.dto.PullRequestDto;
import com.codepilot.model.User;
import com.codepilot.service.PullRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;

import java.util.UUID;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/repositories")
@RequiredArgsConstructor
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final ReviewService reviewService;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final PullRequestService pullRequestService;

    @GetMapping
    public PageDto<RepositoryDto> getRepositories(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return repositoryService.getRepositories(pageable);
    }

    @GetMapping("/{id}")
    public RepositoryDto getRepository(@PathVariable UUID id) {
        return repositoryService.getRepository(id);
    }

    @PostMapping("/sync")
    public PageDto<RepositoryDto> synchronizeRepositories(
            @PageableDefault(size = 100) Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        gitHubIntegrationService.synchronizeAllRepositories(user);
        return repositoryService.getRepositories(pageable);
    }

    @GetMapping("/{id}/pull-requests")
    public PageDto<PullRequestDto> getRepositoryPullRequests(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return pullRequestService.getPullRequestsForRepository(id, pageable);
    }

    @PostMapping("/{id}/pull-requests/sync")
    public PageDto<PullRequestDto> synchronizePullRequests(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        gitHubIntegrationService.synchronizeAllPullRequests(user, repositoryService.getOwnedRepository(id));
        return pullRequestService.getPullRequestsForRepository(id, pageable);
    }

    @GetMapping("/{id}/github-pull-requests")
    public List<Map<String, Object>> getGitHubPullRequests(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        return gitHubIntegrationService.getPullRequests(
                user, repositoryService.getOwnedRepository(id),
                pageable.getPageNumber() + 1, pageable.getPageSize());
    }

    @GetMapping("/{id}/github-pull-requests/{number}")
    public Map<String, Object> getGitHubPullRequest(
            @PathVariable UUID id, @PathVariable int number) {
        User user = repositoryService.getAuthenticatedUser();
        return gitHubIntegrationService.getPullRequestDetails(
                user, repositoryService.getOwnedRepository(id), number);
    }

    @GetMapping("/{id}/github-pull-requests/{number}/diff")
    public String getGitHubPullRequestDiff(
            @PathVariable UUID id, @PathVariable int number) {
        User user = repositoryService.getAuthenticatedUser();
        String diff = gitHubIntegrationService.getPullRequestDiff(
                user, repositoryService.getOwnedRepository(id), number);
        if (diff == null || diff.isBlank()) {
            throw new IllegalStateException("GitHub returned an empty pull request diff");
        }
        return diff;
    }

    @PostMapping("/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto createReview(@PathVariable UUID id, @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.createReview(id, request);
    }
}
