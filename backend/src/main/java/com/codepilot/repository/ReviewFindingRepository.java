package com.codepilot.repository;

import com.codepilot.model.ReviewFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, UUID> {
    Page<ReviewFinding> findByReviewId(UUID reviewId, Pageable pageable);
    long countByReviewPullRequestRepositoryOwnerId(UUID ownerId);
    long countByReviewPullRequestRepositoryOwnerIdAndSeverity(UUID ownerId, String severity);
}
