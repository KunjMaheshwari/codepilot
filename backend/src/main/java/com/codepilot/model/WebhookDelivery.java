package com.codepilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "delivery_id", nullable = false, unique = true, length = 100)
    private String deliveryId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(length = 50)
    private String action;

    @Column(length = 255)
    private String repositoryGithubId;

    private Integer pullRequestNumber;

    @Column(nullable = false, length = 30)
    private String processingStatus;

    @Column(nullable = false)
    private Integer attemptCount = 0;

    @Column(nullable = false)
    private ZonedDateTime receivedAt;

    private ZonedDateTime processedAt;
    private ZonedDateTime nextAttemptAt;

    @Column(length = 50)
    private String errorCategory;

    @Column(columnDefinition = "TEXT")
    private String safeErrorMessage;
}
