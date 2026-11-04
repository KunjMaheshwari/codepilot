package com.codepilot.service;

import com.codepilot.dto.PageDto;
import com.codepilot.dto.RepositoryDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepositoryService {

    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;

    public PageDto<RepositoryDto> getRepositories(Pageable pageable) {
        User user = getAuthenticatedUser();
        Page<Repository> repositories = repositoryRepository.findByOwnerId(user.getId(), pageable);
        return new PageDto<>(repositories.map(RepositoryDto::new));
    }

    public RepositoryDto getRepository(UUID id) {
        User user = getAuthenticatedUser();
        Repository repository = repositoryRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Repository", "id", id));
        return new RepositoryDto(repository);
    }

    public User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user found");
        }

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        if (email == null) {
            String login = oauth2User.getAttribute("login");
            email = login + "@users.noreply.github.com";
        }

        String finalEmail = email;
        return userRepository.findByEmail(finalEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", finalEmail));
    }
}
