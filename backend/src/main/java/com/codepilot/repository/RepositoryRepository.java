package com.codepilot.repository;

import com.codepilot.model.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    Page<Repository> findByOwnerId(UUID ownerId, Pageable pageable);
    Optional<Repository> findByIdAndOwnerId(UUID id, UUID ownerId);
    Optional<Repository> findByGithubRepoId(String githubRepoId);
}
