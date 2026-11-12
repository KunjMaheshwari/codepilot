package com.codepilot.repository;

import com.codepilot.model.GitHubCommentPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GitHubCommentPublicationRepository extends JpaRepository<GitHubCommentPublication, UUID> {
    Optional<GitHubCommentPublication> findByIdempotencyKey(String idempotencyKey);
    List<GitHubCommentPublication> findByReviewId(UUID reviewId);
}

