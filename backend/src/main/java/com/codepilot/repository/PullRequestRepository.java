package com.codepilot.repository;

import com.codepilot.model.PullRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {
    Page<PullRequest> findByRepositoryId(UUID repositoryId, Pageable pageable);
    Optional<PullRequest> findByRepositoryIdAndPrNumber(UUID repositoryId, Integer prNumber);
    Page<PullRequest> findByRepositoryIdAndRepositoryOwnerId(UUID repositoryId, UUID ownerId, Pageable pageable);
    Page<PullRequest> findByRepositoryOwnerId(UUID ownerId, Pageable pageable);
    @EntityGraph(attributePaths = {"repository", "repository.owner"})
    Optional<PullRequest> findByIdAndRepositoryOwnerId(UUID id, UUID ownerId);
}
