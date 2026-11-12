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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepositoryService {

    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final ConnectedGitHubAccountRepository connectedGitHubAccountRepository;

    @Transactional(readOnly = true)
    public PageDto<RepositoryDto> getRepositories(Pageable pageable) {
        User user = getAuthenticatedUser();
        Page<Repository> repositories = repositoryRepository.findByOwnerId(user.getId(), pageable);
        return new PageDto<>(repositories.map(RepositoryDto::new));
    }

    @Transactional(readOnly = true)
    public RepositoryDto getRepository(UUID id) {
        Repository repository = getOwnedRepository(id);
        return new RepositoryDto(repository);
    }

    @Transactional(readOnly = true)
    public Repository getOwnedRepository(UUID id) {
        User user = getAuthenticatedUser();
        return repositoryRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", "id", id));
    }

    public User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            throw new IllegalStateException("No authenticated user found");
        }

        String email = oauth2User.getAttribute("email");
        if (email == null) {
            String login = oauth2User.getAttribute("login");
            if (login == null || login.isBlank()) {
                throw new IllegalStateException("Authenticated user has no usable identity");
            }
            email = login + "@users.noreply.github.com";
        }

        String finalEmail = email;
        return userRepository.findByEmail(finalEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", finalEmail));
    }
}
