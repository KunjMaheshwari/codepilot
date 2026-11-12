package com.codepilot.dto;

import com.codepilot.model.ReviewJob;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.util.UUID;

@Getter
public class ReviewJobStatusDto {
    private final UUID jobId;
    private final UUID reviewId;
    private final String status;
    private final Integer attemptCount;
    private final ZonedDateTime createdAt;
    private final ZonedDateTime startedAt;
    private final ZonedDateTime completedAt;
    private final String errorMessage;

    public ReviewJobStatusDto(ReviewJob job) {
        this.jobId = job.getId();
        this.reviewId = job.getReview().getId();
        this.status = job.getStatus();
        this.attemptCount = job.getAttemptCount();
        this.createdAt = job.getCreatedAt();
        this.startedAt = job.getStartedAt();
        this.completedAt = job.getCompletedAt();
        this.errorMessage = job.getErrorMessage();
    }
}
