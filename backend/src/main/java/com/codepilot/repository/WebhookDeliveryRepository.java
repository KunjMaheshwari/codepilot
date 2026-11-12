package com.codepilot.repository;

import com.codepilot.model.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByDeliveryId(String deliveryId);

    List<WebhookDelivery> findTop50ByProcessingStatusInAndNextAttemptAtLessThanEqualOrderByReceivedAtAsc(
            List<String> statuses, ZonedDateTime now);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE webhook_deliveries
        SET processing_status = 'PROCESSING',
            attempt_count = COALESCE(attempt_count, 0) + 1
        WHERE id = :id
          AND processing_status IN ('RECEIVED', 'FAILED')
          AND COALESCE(attempt_count, 0) < :maxAttempts
          AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
        """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts);
}
