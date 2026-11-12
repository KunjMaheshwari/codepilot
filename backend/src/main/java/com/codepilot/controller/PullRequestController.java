package com.codepilot.controller;

import com.codepilot.dto.PageDto;
import com.codepilot.dto.PullRequestDto;
import com.codepilot.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pull-requests")
@RequiredArgsConstructor
public class PullRequestController {

    private final PullRequestService pullRequestService;

    @GetMapping
    public PageDto<PullRequestDto> getPullRequests(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return pullRequestService.getPullRequests(pageable);
    }

    @GetMapping("/{id}")
    public PullRequestDto getPullRequest(@PathVariable UUID id) {
        return pullRequestService.getPullRequest(id);
    }
}
