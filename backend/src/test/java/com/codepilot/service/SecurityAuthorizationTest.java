package com.codepilot.service;

import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityAuthorizationTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @Mock
    private OAuth2User oAuth2User;

    @InjectMocks
    private RepositoryService repositoryService;

    private User userA;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        userA = new User();
        userA.setId(UUID.randomUUID());
        userA.setEmail("usera@example.com");

        repositoryId = UUID.randomUUID();

        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void getRepository_ShouldThrowResourceNotFoundException_WhenRepositoryBelongsToAnotherUser() {
        // Mock authentication context for User A
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(oAuth2User);
        when(oAuth2User.getAttribute("email")).thenReturn(userA.getEmail());
        when(userRepository.findByEmail(userA.getEmail())).thenReturn(Optional.of(userA));

        // The repository exists but doesn't belong to User A, so findByIdAndOwnerId returns empty
        when(repositoryRepository.findByIdAndOwnerId(repositoryId, userA.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> repositoryService.getRepository(repositoryId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Repository not found");
    }
}
