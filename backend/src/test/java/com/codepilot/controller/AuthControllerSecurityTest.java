package com.codepilot.controller;

import com.codepilot.model.User;
import com.codepilot.service.GitHubIntegrationService;
import com.codepilot.service.RepositoryService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerSecurityTest {

    @Test
    void disconnectGitHubShouldUseAuthenticatedUserFromSecurityContext() {
        GitHubIntegrationService gitHubIntegrationService = mock(GitHubIntegrationService.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        User authenticatedUser = new User();
        authenticatedUser.setId(UUID.randomUUID());
        when(repositoryService.getAuthenticatedUser()).thenReturn(authenticatedUser);

        AuthController controller = new AuthController(gitHubIntegrationService, repositoryService);

        controller.disconnectGitHub();

        verify(repositoryService).getAuthenticatedUser();
        verify(gitHubIntegrationService).disconnectGitHub(authenticatedUser);
    }
}
