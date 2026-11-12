package com.codepilot.dto;

import com.codepilot.model.Repository;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
public class RepositoryDto {
    private UUID id;
    private String name;
    private String description;
    private String fullName;
    private UUID ownerId;
    private String githubRepoId;
    private Boolean isActive;
    private Boolean isPrivate;
    private String defaultBranch;
    private String htmlUrl;
    private ZonedDateTime lastSyncedAt;
    private ZonedDateTime createdAt;
    private ZonedDateTime updatedAt;

    public RepositoryDto(Repository repository) {
        this.id = repository.getId();
        this.name = repository.getName();
        this.description = repository.getDescription();
        this.fullName = repository.getFullName();
        this.ownerId = repository.getOwner() != null ? repository.getOwner().getId() : null;
        this.githubRepoId = repository.getGithubRepoId();
        this.isActive = repository.getIsActive();
        this.isPrivate = repository.getIsPrivate();
        this.defaultBranch = repository.getDefaultBranch();
        this.htmlUrl = repository.getHtmlUrl();
        this.lastSyncedAt = repository.getLastSyncedAt();
        this.createdAt = repository.getCreatedAt();
        this.updatedAt = repository.getUpdatedAt();
    }
}
