package com.codepilot.dto;

import com.codepilot.model.Review;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
public class ReviewDto {
    private UUID id;
    private UUID pullRequestId;
    private UUID jobId;
    private String status;
    private ZonedDateTime startedAt;
    private ZonedDateTime completedAt;
    private ZonedDateTime createdAt;

    public ReviewDto(Review review) {
        this(review, null);
    }

    public ReviewDto(Review review, UUID jobId) {
        this.id = review.getId();
        this.pullRequestId = review.getPullRequest() != null ? review.getPullRequest().getId() : null;
        this.jobId = jobId;
        this.status = review.getStatus();
        this.startedAt = review.getStartedAt();
        this.completedAt = review.getCompletedAt();
        this.createdAt = review.getCreatedAt();
    }
}
