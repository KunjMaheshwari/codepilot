package com.codepilot.service;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubIntegrationServiceTest {

    @Mock
    private GitHubApiClient gitHubApiClient;

    @Mock
    private ConnectedGitHubAccountRepository connectedGitHubAccountRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private GitHubIntegrationService gitHubIntegrationService;

    private User testUser;
    private ConnectedGitHubAccount connectedAccount;
    private Repository testRepository;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");

        connectedAccount = new ConnectedGitHubAccount();
        connectedAccount.setUser(testUser);
        connectedAccount.setAccessToken("test-token");

        testRepository = new Repository();
        testRepository.setFullName("owner/repo");
    }

    @Test
    void getRepositoriesForUser_Success() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");
        when(gitHubApiClient.getUserRepositories("decrypted-token", 1, 10))
                .thenReturn(List.of(Map.of("name", "test-repo")));

        List<Map<String, Object>> repos = gitHubIntegrationService.getRepositoriesForUser(testUser, 1, 10);

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0)).containsEntry("name", "test-repo");
    }

    @Test
    void getRepositoriesForUser_NoConnectedAccount() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> gitHubIntegrationService.getRepositoriesForUser(testUser, 1, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User does not have a connected GitHub account");
    }

    @Test
    void getPullRequestDiff_Success() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");
        when(gitHubApiClient.getPullRequestDiff("decrypted-token", "owner", "repo", 1))
                .thenReturn("mock diff content");

        String diff = gitHubIntegrationService.getPullRequestDiff(testUser, testRepository, 1);

        assertThat(diff).isEqualTo("mock diff content");
    }
}
