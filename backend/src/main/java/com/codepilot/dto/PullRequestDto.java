package com.codepilot.dto;

import com.codepilot.model.PullRequest;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
public class PullRequestDto {
    private UUID id;
    private UUID repositoryId;
    private Integer prNumber;
    private String title;
    private String author;
    private String status;
    private ZonedDateTime createdAt;
    private ZonedDateTime updatedAt;

    public PullRequestDto(PullRequest pullRequest) {
        this.id = pullRequest.getId();
        this.repositoryId = pullRequest.getRepository() != null ? pullRequest.getRepository().getId() : null;
        this.prNumber = pullRequest.getPrNumber();
        this.title = pullRequest.getTitle();
        this.author = pullRequest.getAuthor();
        this.status = pullRequest.getStatus();
        this.createdAt = pullRequest.getCreatedAt();
        this.updatedAt = pullRequest.getUpdatedAt();
    }
}
