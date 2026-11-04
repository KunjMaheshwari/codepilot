package com.codepilot.repository;

import com.codepilot.model.ConnectedGitHubAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectedGitHubAccountRepository extends JpaRepository<ConnectedGitHubAccount, UUID> {
    Optional<ConnectedGitHubAccount> findByUserId(UUID userId);
    Optional<ConnectedGitHubAccount> findByGithubUserId(String githubUserId);
}
