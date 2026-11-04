package com.codepilot.controller;

import com.codepilot.dto.CreateReviewRequest;
import com.codepilot.dto.PageDto;
import com.codepilot.dto.RepositoryDto;
import com.codepilot.dto.ReviewDto;
import com.codepilot.service.RepositoryService;
import com.codepilot.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repositories")
@RequiredArgsConstructor
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final ReviewService reviewService;

    @GetMapping
    public PageDto<RepositoryDto> getRepositories(Pageable pageable) {
        return repositoryService.getRepositories(pageable);
    }

    @GetMapping("/{id}")
    public RepositoryDto getRepository(@PathVariable UUID id) {
        return repositoryService.getRepository(id);
    }

    @PostMapping("/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto createReview(@PathVariable UUID id, @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.createReview(id, request);
    }
}
