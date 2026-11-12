package com.codepilot.service;

import java.time.ZonedDateTime;
import java.util.UUID;

public record ReviewJobMessage(
        UUID jobId,
        UUID reviewId,
        UUID pullRequestId,
        UUID repositoryId,
        ZonedDateTime createdAt,
        String correlationId) {
}
