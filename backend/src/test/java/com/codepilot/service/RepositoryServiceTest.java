package com.codepilot.service;

import com.codepilot.dto.PageDto;
import com.codepilot.dto.RepositoryDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryServiceTest {

    @Mock
    private RepositoryRepository repositoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConnectedGitHubAccountRepository connectedGitHubAccountRepository;

    @InjectMocks
    private RepositoryService repositoryService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getRepositoriesScopesByAuthenticatedUserAndMapsDto() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("octocat");
        owner.setEmail("octocat@example.com");

        authenticateWithGitHubId("42", "octocat", "octocat@example.com");

        ConnectedGitHubAccount account = new ConnectedGitHubAccount();
        account.setUser(owner);
        when(connectedGitHubAccountRepository.findByGithubUserId("42")).thenReturn(Optional.of(account));

        Repository repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setOwner(owner);
        repo.setName("codepilot");
        repo.setFullName("octocat/codepilot");
        repo.setIsActive(true);

        PageRequest pageable = PageRequest.of(0, 10);
        when(repositoryRepository.findByOwnerId(owner.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(repo), pageable, 1));

        PageDto<RepositoryDto> result = repositoryService.getRepositories(pageable);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(repo.getId());
            assertThat(dto.getFullName()).isEqualTo("octocat/codepilot");
        });
    }

    @Test
    void getOwnedRepositoryThrowsNotFoundWhenMissingOrDifferentOwner() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        authenticateWithGitHubId("42", "octocat", null);

        when(connectedGitHubAccountRepository.findByGithubUserId("42")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("octocat@users.noreply.github.com")).thenReturn(Optional.of(owner));

        UUID missingId = UUID.randomUUID();
        when(repositoryRepository.findByIdAndOwnerId(missingId, owner.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repositoryService.getRepository(missingId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAuthenticatedUserThrowsWhenSecurityContextIsUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> repositoryService.getAuthenticatedUser())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No authenticated user found");
    }

    private void authenticateWithGitHubId(String id, String login, String email) {
        Map<String, Object> attrs = email != null
                ? Map.of("id", id, "login", login, "email", email)
                : Map.of("id", id, "login", login);
        OAuth2User principal = new DefaultOAuth2User(List.of(), attrs, "login");
        TestingAuthenticationToken token = new TestingAuthenticationToken(principal, null, "ROLE_USER");
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
