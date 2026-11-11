package com.codepilot.service;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class GitHubIntegrationService {

    private final GitHubApiClient gitHubApiClient;
    private final ConnectedGitHubAccountRepository connectedGitHubAccountRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

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
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String decryptedToken = encryptionService.decrypt(account.getAccessToken());
        return gitHubApiClient.getUserRepositories(decryptedToken, page, perPage);
    }

    public Map<String, Object> getPullRequestDetails(User user, Repository repository, int prNumber) {
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String decryptedToken = encryptionService.decrypt(account.getAccessToken());
        String owner = repository.getFullName().split("/")[0];
        String repoName = repository.getFullName().split("/")[1];
        return gitHubApiClient.getPullRequest(decryptedToken, owner, repoName, prNumber);
    }

    public String getPullRequestDiff(User user, Repository repository, int prNumber) {
        ConnectedGitHubAccount account = getConnectedAccount(user);
        String decryptedToken = encryptionService.decrypt(account.getAccessToken());
        String owner = repository.getFullName().split("/")[0];
        String repoName = repository.getFullName().split("/")[1];
        return gitHubApiClient.getPullRequestDiff(decryptedToken, owner, repoName, prNumber);
    }

    private ConnectedGitHubAccount getConnectedAccount(User user) {
        return connectedGitHubAccountRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("User does not have a connected GitHub account"));
    }
}
