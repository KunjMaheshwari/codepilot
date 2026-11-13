package com.codepilot.service;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.Repository;
import com.codepilot.model.PullRequest;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.PullRequestRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class GitHubIntegrationServiceTest {

    @Mock
    private GitHubApiClient gitHubApiClient;

    @Mock
    private ConnectedGitHubAccountRepository connectedGitHubAccountRepository;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

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

    @Test
    void linkAccount_ShouldRejectAccountAlreadyOwnedByAnotherUser() {
        User anotherUser = new User();
        anotherUser.setId(UUID.randomUUID());
        connectedAccount.setUser(anotherUser);
        when(connectedGitHubAccountRepository.findByGithubUserId("github-user"))
                .thenReturn(Optional.of(connectedAccount));

        assertThatThrownBy(() -> gitHubIntegrationService.linkAccount(
                testUser, "github-user", "github-user", "raw-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub account is already connected to another user");
    }

    @Test
    void synchronizePullRequestsMapsMetadataAndUpsertsByRepositoryAndNumber() {
        testRepository.setId(UUID.randomUUID());
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");
        when(gitHubApiClient.getPullRequests("decrypted-token", "owner", "repo", 1, 20))
                .thenReturn(List.of(Map.of(
                        "number", 7,
                        "title", "Improve review",
                        "state", "closed",
                        "user", Map.of("login", "contributor"),
                        "base", Map.of("ref", "main", "sha", "base-sha"),
                        "head", Map.of("ref", "feature", "sha", "head-sha"),
                        "additions", 12,
                        "deletions", 4,
                        "changed_files", 2)));
        when(pullRequestRepository.findByRepositoryIdAndPrNumber(testRepository.getId(), 7))
                .thenReturn(Optional.empty());
        when(pullRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<PullRequest> result = gitHubIntegrationService
                .synchronizePullRequests(testUser, testRepository, 1, 20);

        assertThat(result).singleElement().satisfies(pr -> {
            assertThat(pr.getPrNumber()).isEqualTo(7);
            assertThat(pr.getBaseSha()).isEqualTo("base-sha");
            assertThat(pr.getHeadSha()).isEqualTo("head-sha");
            assertThat(pr.getAdditions()).isEqualTo(12);
            assertThat(pr.getChangedFiles()).isEqualTo(2);
        });
        verify(pullRequestRepository).save(any(PullRequest.class));
    }

    @Test
    void synchronizeRepositoriesUpdatesExistingRecordWithoutCreatingDuplicate() {
        Repository existing = new Repository();
        existing.setId(UUID.randomUUID());
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");
        when(gitHubApiClient.getUserRepositories("decrypted-token", 1, 10))
                .thenReturn(List.of(Map.of(
                        "id", 42,
                        "name", "repo",
                        "full_name", "owner/repo",
                        "private", true,
                        "default_branch", "main",
                        "html_url", "https://github.com/owner/repo")));
        when(repositoryRepository.findByGithubRepoIdAndOwnerId("42", testUser.getId()))
                .thenReturn(Optional.of(existing));
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Repository> result = gitHubIntegrationService
                .synchronizeRepositories(testUser, 1, 10);

        assertThat(result).singleElement().isSameAs(existing);
        assertThat(existing.getGithubRepoId()).isEqualTo("42");
        assertThat(existing.getIsPrivate()).isTrue();
        verify(repositoryRepository).save(existing);
    }

    @Test
    void synchronizeAllRepositoriesMarksArchivedAndMissingRepositoriesInactive() {
        Repository removed = new Repository();
        removed.setId(UUID.randomUUID());
        removed.setGithubRepoId("999");
        removed.setIsActive(true);

        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");
        when(gitHubApiClient.getUserRepositories("decrypted-token", 1, 100))
                .thenReturn(List.of(Map.of(
                        "id", 42,
                        "name", "archived-repo",
                        "full_name", "owner/archived-repo",
                        "private", false,
                        "archived", true,
                        "default_branch", "main",
                        "html_url", "https://github.com/owner/archived-repo")));
        when(repositoryRepository.findByGithubRepoIdAndOwnerId("42", testUser.getId()))
                .thenReturn(Optional.empty());
        when(repositoryRepository.findByOwnerIdAndIsActiveTrue(testUser.getId()))
                .thenReturn(List.of(removed));
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Repository> result = gitHubIntegrationService.synchronizeAllRepositories(testUser);

        assertThat(result).singleElement().satisfies(repo -> {
            assertThat(repo.getGithubRepoId()).isEqualTo("42");
            assertThat(repo.getIsActive()).isFalse();
        });
    }

    @Test
    void synchronizeAllRepositories_MultiplePagesWithMoreThan250Repositories() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");

        // Page 1: 100 repositories
        List<Map<String, Object>> page1 = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            page1.add(Map.of("id", i, "name", "repo-" + i, "full_name", "owner/repo-" + i, "private", false));
        }
        // Page 2: 100 repositories
        List<Map<String, Object>> page2 = new java.util.ArrayList<>();
        for (int i = 101; i <= 200; i++) {
            page2.add(Map.of("id", i, "name", "repo-" + i, "full_name", "owner/repo-" + i, "private", false));
        }
        // Page 3: 55 repositories (total 255)
        List<Map<String, Object>> page3 = new java.util.ArrayList<>();
        for (int i = 201; i <= 255; i++) {
            page3.add(Map.of("id", i, "name", "repo-" + i, "full_name", "owner/repo-" + i, "private", false));
        }

        when(gitHubApiClient.getUserRepositoriesPage("decrypted-token", 1, 100))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page1, "https://api.github.test/user/repos?page=2"));
        when(gitHubApiClient.getUserRepositoriesPageByUrl("decrypted-token", "https://api.github.test/user/repos?page=2"))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page2, "https://api.github.test/user/repos?page=3"));
        when(gitHubApiClient.getUserRepositoriesPageByUrl("decrypted-token", "https://api.github.test/user/repos?page=3"))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page3, null));

        when(repositoryRepository.findByGithubRepoIdAndOwnerId(anyString(), any()))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Repository> result = gitHubIntegrationService.synchronizeAllRepositories(testUser);

        assertThat(result).hasSize(255);
        assertThat(result.get(0).getName()).isEqualTo("repo-1");
        assertThat(result.get(254).getName()).isEqualTo("repo-255");
    }

    @Test
    void synchronizeAllRepositories_DeduplicatesRepositoriesAcrossPages() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");

        // Page 1 contains repo 1 and repo 2
        List<Map<String, Object>> page1 = List.of(
                Map.of("id", 1, "name", "repo-1", "full_name", "owner/repo-1", "private", false),
                Map.of("id", 2, "name", "repo-2", "full_name", "owner/repo-2", "private", false));
        // Page 2 contains repo 2 (duplicate) and repo 3
        List<Map<String, Object>> page2 = List.of(
                Map.of("id", 2, "name", "repo-2", "full_name", "owner/repo-2", "private", false),
                Map.of("id", 3, "name", "repo-3", "full_name", "owner/repo-3", "private", false));

        when(gitHubApiClient.getUserRepositoriesPage("decrypted-token", 1, 100))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page1, "https://api.github.test/user/repos?page=2"));
        when(gitHubApiClient.getUserRepositoriesPageByUrl("decrypted-token", "https://api.github.test/user/repos?page=2"))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page2, null));

        when(repositoryRepository.findByGithubRepoIdAndOwnerId(anyString(), any()))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Repository> result = gitHubIntegrationService.synchronizeAllRepositories(testUser);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(Repository::getGithubRepoId)).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    void synchronizeAllRepositories_EmptyFinalPageStopsPaginationSafely() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");

        // Exactly 100 items on page 1 with next Link header pointing to page 2
        List<Map<String, Object>> page1 = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            page1.add(Map.of("id", i, "name", "repo-" + i, "full_name", "owner/repo-" + i, "private", false));
        }
        // Page 2 is empty with no next link
        when(gitHubApiClient.getUserRepositoriesPage("decrypted-token", 1, 100))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page1, "https://api.github.test/user/repos?page=2"));
        when(gitHubApiClient.getUserRepositoriesPageByUrl("decrypted-token", "https://api.github.test/user/repos?page=2"))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(List.of(), null));

        when(repositoryRepository.findByGithubRepoIdAndOwnerId(anyString(), any()))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Repository> result = gitHubIntegrationService.synchronizeAllRepositories(testUser);

        assertThat(result).hasSize(100);
    }

    @Test
    void synchronizeAllRepositories_EnforcesTenantIsolation() {
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        otherUser.setUsername("otheruser");

        ConnectedGitHubAccount otherAccount = new ConnectedGitHubAccount();
        otherAccount.setUser(otherUser);
        otherAccount.setAccessToken("other-token");

        when(connectedGitHubAccountRepository.findByUserId(otherUser.getId()))
                .thenReturn(Optional.of(otherAccount));
        when(encryptionService.decrypt("other-token")).thenReturn("decrypted-other-token");

        when(gitHubApiClient.getUserRepositoriesPage("decrypted-other-token", 1, 100))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(List.of(
                        Map.of("id", 888, "name", "tenant-repo", "full_name", "other/tenant-repo", "private", false)), null));

        when(repositoryRepository.findByGithubRepoIdAndOwnerId("888", otherUser.getId()))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Repository> result = gitHubIntegrationService.synchronizeAllRepositories(otherUser);

        assertThat(result).singleElement().satisfies(repo -> {
            assertThat(repo.getOwner().getId()).isEqualTo(otherUser.getId());
            assertThat(repo.getOwner().getUsername()).isEqualTo("otheruser");
        });
    }

    @Test
    void synchronizeAllRepositories_NetworkFailureDuringPaginationThrowsAndDoesNotMarkMissingInactive() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");

        List<Map<String, Object>> page1 = List.of(
                Map.of("id", 1, "name", "repo-1", "full_name", "owner/repo-1", "private", false));

        when(repositoryRepository.findByGithubRepoIdAndOwnerId("1", testUser.getId()))
                .thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(gitHubApiClient.getUserRepositoriesPage("decrypted-token", 1, 100))
                .thenReturn(new GitHubApiClient.GitHubPagedResponse<>(page1, "https://api.github.test/user/repos?page=2"));
        when(gitHubApiClient.getUserRepositoriesPageByUrl("decrypted-token", "https://api.github.test/user/repos?page=2"))
                .thenThrow(new com.codepilot.exception.GitHubApiException("GitHub API is unavailable", org.springframework.http.HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> gitHubIntegrationService.synchronizeAllRepositories(testUser))
                .isInstanceOf(com.codepilot.exception.GitHubApiException.class)
                .hasMessage("GitHub API is unavailable");
    }

    @Test
    void synchronizeAllPullRequestsMarksMergedPullRequestAsMerged() {
        when(connectedGitHubAccountRepository.findByUserId(testUser.getId()))
                .thenReturn(Optional.of(connectedAccount));
        when(encryptionService.decrypt("test-token")).thenReturn("decrypted-token");
        when(gitHubApiClient.getPullRequests("decrypted-token", "owner", "repo", 1, 100))
                .thenReturn(List.of(Map.of(
                        "number", 12,
                        "title", "Merged PR",
                        "state", "closed",
                        "merged_at", "2026-01-01T00:00:00Z",
                        "user", Map.of("login", "author"),
                        "base", Map.of("ref", "main", "sha", "base-sha"),
                        "head", Map.of("ref", "feat", "sha", "head-sha"))));
        when(pullRequestRepository.findByRepositoryIdAndPrNumber(testRepository.getId(), 12))
                .thenReturn(Optional.empty());
        when(pullRequestRepository.save(any(PullRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<PullRequest> result = gitHubIntegrationService.synchronizeAllPullRequests(testUser, testRepository);

        assertThat(result).singleElement().satisfies(pr -> {
            assertThat(pr.getPrNumber()).isEqualTo(12);
            assertThat(pr.getStatus()).isEqualTo("merged");
        });
    }
}
