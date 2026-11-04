package com.codepilot.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
public class CreateReviewRequest {
    @NotNull(message = "Pull request ID is required")
    private UUID pullRequestId;
}
