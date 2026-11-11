package com.codepilot.repository;

import com.codepilot.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {
    Page<Review> findByPullRequestRepositoryOwnerId(UUID ownerId, Pageable pageable);
    Page<Review> findByPullRequestId(UUID pullRequestId, Pageable pageable);
    Optional<Review> findByIdAndPullRequestRepositoryOwnerId(UUID id, UUID ownerId);
    long countByPullRequestRepositoryOwnerId(UUID ownerId);
}
