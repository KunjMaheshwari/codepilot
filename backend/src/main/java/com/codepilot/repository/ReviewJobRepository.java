package com.codepilot.repository;

import com.codepilot.model.ReviewJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.UUID;
import java.util.List;
import java.time.ZonedDateTime;

@Repository
public interface ReviewJobRepository extends JpaRepository<ReviewJob, UUID> {
    @EntityGraph(attributePaths = {"review", "review.pullRequest", "review.pullRequest.repository"})
    List<ReviewJob> findTop50ByStatusOrderByCreatedAtAsc(String status);

    @EntityGraph(attributePaths = {"review", "review.pullRequest", "review.pullRequest.repository"})
    @Query("""
        select j from ReviewJob j
        where j.status = 'QUEUED'
          and j.updatedAt < :cutoff
        order by j.updatedAt asc
        """)
    List<ReviewJob> findStaleQueuedJobs(@Param("cutoff") ZonedDateTime cutoff);
    java.util.Optional<ReviewJob> findByReviewId(UUID reviewId);

    @EntityGraph(attributePaths = {"review", "review.pullRequest", "review.pullRequest.repository",
            "review.pullRequest.repository.owner"})
    java.util.Optional<ReviewJob> findWithReviewById(UUID id);

    @Modifying
    @Query(value = """
        UPDATE review_jobs SET status = 'PROCESSING',
        attempt_count = COALESCE(attempt_count, 0) + 1,
        started_at = CURRENT_TIMESTAMP,
        processing_heartbeat_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status IN ('QUEUED', 'RETRYING', 'PENDING')
        """, nativeQuery = true)
    int claim(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        UPDATE review_jobs SET processing_heartbeat_at = CURRENT_TIMESTAMP
        WHERE id IN (:ids) AND status = 'PROCESSING'
        """, nativeQuery = true)
    int touchProcessingJobs(@Param("ids") java.util.Collection<UUID> ids);

    @Query("""
        select j from ReviewJob j
        where j.status = :status
          and (j.processingHeartbeatAt < :cutoff
               or (j.processingHeartbeatAt is null and j.startedAt < :cutoff))
        """)
    List<ReviewJob> findStaleProcessingJobs(
            @Param("status") String status, @Param("cutoff") ZonedDateTime cutoff);
}
