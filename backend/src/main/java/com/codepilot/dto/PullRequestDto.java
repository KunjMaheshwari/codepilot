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
    private String baseBranch;
    private String headBranch;
    private String baseSha;
    private String headSha;
    private Integer additions;
    private Integer deletions;
    private Integer changedFiles;
    private ZonedDateTime createdAt;
    private ZonedDateTime updatedAt;

    public PullRequestDto(PullRequest pullRequest) {
        this.id = pullRequest.getId();
        this.repositoryId = pullRequest.getRepository() != null ? pullRequest.getRepository().getId() : null;
        this.prNumber = pullRequest.getPrNumber();
        this.title = pullRequest.getTitle();
        this.author = pullRequest.getAuthor();
        this.status = pullRequest.getStatus();
        this.baseBranch = pullRequest.getBaseBranch();
        this.headBranch = pullRequest.getHeadBranch();
        this.baseSha = pullRequest.getBaseSha();
        this.headSha = pullRequest.getHeadSha();
        this.additions = pullRequest.getAdditions();
        this.deletions = pullRequest.getDeletions();
        this.changedFiles = pullRequest.getChangedFiles();
        this.createdAt = pullRequest.getCreatedAt();
        this.updatedAt = pullRequest.getUpdatedAt();
    }
}
