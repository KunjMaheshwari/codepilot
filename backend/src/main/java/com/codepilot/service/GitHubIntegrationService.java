package com.codepilot.service;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.PullRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZonedDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class GitHubIntegrationService {

    private static final int MAX_SYNC_PAGES = 10;
    private static final int DEFAULT_SYNC_PAGE_SIZE = 100;

    private final GitHubApiClient gitHubApiClient;
    private final ConnectedGitHubAccountRepository connectedGitHubAccountRepository;
    private final EncryptionService encryptionService;
    private final RepositoryRepository repositoryRepository;
    private final PullRequestRepository pullRequestRepository;

    @Transactional
    public void linkAccount(User user, String githubUserId, String githubUsername, String rawAccessToken) {
        connectedGitHubAccountRepository.findByGithubUserId(githubUserId)
                .filter(existing -> !existing.getUser().getId().equals(user.getId()))
                .ifPresent(existing -> {
                    throw new IllegalStateException("GitHub account is already connected to another user");
                });

        ConnectedGitHubAccount account = connectedGitHubAccountRepository.findByUserId(user.getId())
                .orElse(new ConnectedGitHubAccount());
        account.setUser(user);
        account.setGithubUserId(githubUserId);
        account.setGithubUsername(githubUsername);
        account.setAccessToken(encryptionService.encrypt(rawAccessToken));
        connectedGitHubAccountRepository.save(account);
    }

    @Transactional
    public void disconnectGitHub(User user) {
        connectedGitHubAccountRepository.findByUserId(user.getId())
                .ifPresent(connectedGitHubAccountRepository::delete);
    }

    public List<Map<String, Object>> getRepositoriesForUser(User user, int page, int perPage) {
        validatePage(page, perPage);
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String decryptedToken = encryptionService.decrypt(account.getAccessToken());
        return gitHubApiClient.getUserRepositories(decryptedToken, page, perPage);
    }

    /**
     * Synchronizes only the requested bounded page. Records absent from that page
     * remain active because a partial fetch is not evidence that access was
     * revoked.
     */
    @Transactional
    public List<Repository> synchronizeRepositories(User user, int page, int perPage) {
        List<Map<String, Object>> remoteRepositories = getRepositoriesForUser(user, page, perPage);
        return remoteRepositories.stream()
                .map(remote -> upsertRepository(user, remote))
                .peek(repository -> repository.setLastSyncedAt(ZonedDateTime.now()))
                .toList();
    }

    /**
     * Paginates through the user's GitHub repositories and upserts each by
     * immutable
     * GitHub repository ID. Only when full pagination completes does it deactivate
     * previously active repositories that are no longer returned by GitHub.
     */
    @Transactional
    public List<Repository> synchronizeAllRepositories(User user) {
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String token = encryptionService.decrypt(account.getAccessToken());
        List<Repository> synced = new ArrayList<>();
        Set<String> seenGithubRepoIds = new HashSet<>();
        boolean completedFullScan = false;
        ZonedDateTime syncedAt = ZonedDateTime.now();

        for (int page = 1; page <= MAX_SYNC_PAGES; page++) {
            List<Map<String, Object>> pageItems = gitHubApiClient.getUserRepositories(
                    token, page, DEFAULT_SYNC_PAGE_SIZE);
            for (Map<String, Object> remote : pageItems) {
                Repository repository = upsertRepository(user, remote);
                repository.setLastSyncedAt(syncedAt);
                synced.add(repository);
                seenGithubRepoIds.add(repository.getGithubRepoId());
            }
            if (pageItems.size() < DEFAULT_SYNC_PAGE_SIZE) {
                completedFullScan = true;
                break;
            }
        }

        if (completedFullScan) {
            for (Repository existing : repositoryRepository.findByOwnerIdAndIsActiveTrue(user.getId())) {
                if (!seenGithubRepoIds.contains(existing.getGithubRepoId())) {
                    existing.setIsActive(false);
                    existing.setLastSyncedAt(syncedAt);
                    repositoryRepository.save(existing);
                }
            }
        }
        return synced;
    }

    public List<Map<String, Object>> getPullRequests(User user, Repository repository, int page, int perPage) {
        validatePage(page, perPage);
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String token = encryptionService.decrypt(account.getAccessToken());
        String[] parts = splitFullName(repository);
        return gitHubApiClient.getPullRequests(token, parts[0], parts[1], page, perPage);
    }

    /**
     * Upserts pull requests by repository and GitHub PR number. Missing optional
     * GitHub metadata is retained as null rather than fabricated.
     */
    @Transactional
    public List<PullRequest> synchronizePullRequests(
            User user, Repository repository, int page, int perPage) {
        return getPullRequests(user, repository, page, perPage).stream()
                .map(remote -> upsertPullRequest(repository, remote))
                .toList();
    }

    /**
     * Paginates through all open, closed, and merged pull requests for a
     * repository.
     */
    @Transactional
    public List<PullRequest> synchronizeAllPullRequests(User user, Repository repository) {
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String token = encryptionService.decrypt(account.getAccessToken());
        String[] parts = splitFullName(repository);
        List<PullRequest> synced = new ArrayList<>();

        for (int page = 1; page <= MAX_SYNC_PAGES; page++) {
            List<Map<String, Object>> pageItems = gitHubApiClient.getPullRequests(
                    token, parts[0], parts[1], page, DEFAULT_SYNC_PAGE_SIZE);
            for (Map<String, Object> remote : pageItems) {
                synced.add(upsertPullRequest(repository, remote));
            }
            if (pageItems.size() < DEFAULT_SYNC_PAGE_SIZE) {
                break;
            }
        }
        repository.setLastSyncedAt(ZonedDateTime.now());
        repositoryRepository.save(repository);
        return synced;
    }

    @Transactional
    public PullRequest synchronizePullRequest(User user, Repository repository, int prNumber) {
        return upsertPullRequest(repository, getPullRequestDetails(user, repository, prNumber));
    }

    @Transactional
    public PullRequest persistSynchronizedPullRequest(Repository repository, Map<String, Object> remote) {
        return upsertPullRequest(repository, remote);
    }

    private PullRequest upsertPullRequest(Repository repository, Map<String, Object> remote) {
        Object rawNumber = remote.get("number");
        if (!(rawNumber instanceof Number number)) {
            throw new IllegalArgumentException("GitHub response is missing number");
        }
        PullRequest pullRequest = pullRequestRepository
                .findByRepositoryIdAndPrNumber(repository.getId(), number.intValue())
                .orElseGet(PullRequest::new);
        pullRequest.setRepository(repository);
        pullRequest.setPrNumber(number.intValue());
        pullRequest.setTitle(requiredString(remote, "title"));
        Object rawUser = remote.get("user");
        Object login = rawUser instanceof Map<?, ?> values ? values.get("login") : null;
        pullRequest.setAuthor(login == null ? "unknown" : String.valueOf(login));
        boolean merged = remote.get("merged_at") != null || Boolean.TRUE.equals(remote.get("merged"));
        pullRequest.setStatus(merged ? "merged" : requiredString(remote, "state"));
        Map<?, ?> base = nestedMap(remote, "base");
        Map<?, ?> head = nestedMap(remote, "head");
        pullRequest.setBaseBranch(stringValue(base, "ref"));
        pullRequest.setHeadBranch(stringValue(head, "ref"));
        pullRequest.setBaseSha(stringValue(base, "sha"));
        pullRequest.setHeadSha(stringValue(head, "sha"));
        pullRequest.setAdditions(integerValue(remote, "additions"));
        pullRequest.setDeletions(integerValue(remote, "deletions"));
        pullRequest.setChangedFiles(integerValue(remote, "changed_files"));
        return pullRequestRepository.save(pullRequest);
    }

    private Map<?, ?> nestedMap(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private String stringValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private Integer integerValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private Repository upsertRepository(User user, Map<String, Object> remote) {
        Object rawId = remote.get("id");
        if (rawId == null || String.valueOf(rawId).isBlank()) {
            throw new IllegalArgumentException("GitHub response is missing id");
        }
        String githubRepoId = String.valueOf(rawId);
        Repository repository = repositoryRepository.findByGithubRepoIdAndOwnerId(githubRepoId, user.getId())
                .orElseGet(Repository::new);
        repository.setOwner(user);
        repository.setGithubRepoId(githubRepoId);
        repository.setName(requiredString(remote, "name"));
        repository.setFullName(requiredString(remote, "full_name"));
        repository.setDescription((String) remote.get("description"));
        repository.setIsPrivate(Boolean.TRUE.equals(remote.get("private")));
        repository.setDefaultBranch((String) remote.get("default_branch"));
        repository.setHtmlUrl((String) remote.get("html_url"));
        boolean archived = Boolean.TRUE.equals(remote.get("archived"));
        boolean disabled = Boolean.TRUE.equals(remote.get("disabled"));
        repository.setIsActive(!archived && !disabled);
        return repositoryRepository.save(repository);
    }

    private void validatePage(int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > 100) {
            throw new IllegalArgumentException("page must be >= 1 and perPage must be between 1 and 100");
        }
    }

    private String requiredString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("GitHub response is missing " + key);
        }
        return string;
    }

    public Map<String, Object> getPullRequestDetails(User user, Repository repository, int prNumber) {
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String decryptedToken = encryptionService.decrypt(account.getAccessToken());
        String[] parts = splitFullName(repository);
        return gitHubApiClient.getPullRequest(decryptedToken, parts[0], parts[1], prNumber);
    }

    public String getPullRequestDiff(User user, Repository repository, int prNumber) {
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String decryptedToken = encryptionService.decrypt(account.getAccessToken());
        String[] parts = splitFullName(repository);
        return gitHubApiClient.getPullRequestDiff(decryptedToken, parts[0], parts[1], prNumber);
    }

    private String[] splitFullName(Repository repository) {
        String fullName = repository.getFullName();
        if (fullName == null) {
            throw new IllegalStateException("Repository has an invalid GitHub full name");
        }
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalStateException("Repository has an invalid GitHub full name");
        }
        return parts;
    }

    private ConnectedGitHubAccount getConnectedAccount(User user) {
        return connectedGitHubAccountRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("User does not have a connected GitHub account"));
    }
}
