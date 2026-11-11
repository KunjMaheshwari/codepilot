package com.codepilot.service;

import com.codepilot.dto.PageDto;
import com.codepilot.dto.PullRequestDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.PullRequest;
import com.codepilot.model.User;
import com.codepilot.repository.PullRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PullRequestService {

    private final PullRequestRepository pullRequestRepository;
    private final RepositoryService repositoryService;

    @Transactional(readOnly = true)
    public PageDto<PullRequestDto> getPullRequests(Pageable pageable) {
        User user = repositoryService.getAuthenticatedUser();
        Page<PullRequest> pullRequests = pullRequestRepository.findByRepositoryOwnerId(user.getId(), pageable);
        return new PageDto<>(pullRequests.map(PullRequestDto::new));
    }

    @Transactional(readOnly = true)
    public PullRequestDto getPullRequest(UUID id) {
        User user = repositoryService.getAuthenticatedUser();
        PullRequest pullRequest = pullRequestRepository.findByIdAndRepositoryOwnerId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("PullRequest", "id", id));
        return new PullRequestDto(pullRequest);
    }
}
