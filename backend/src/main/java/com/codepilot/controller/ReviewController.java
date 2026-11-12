package com.codepilot.controller;

import com.codepilot.dto.PageDto;
import com.codepilot.dto.ReviewDto;
import com.codepilot.dto.ReviewFindingDto;
import com.codepilot.dto.ReviewJobStatusDto;
import com.codepilot.service.ReviewService;
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
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public PageDto<ReviewDto> getReviews(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return reviewService.getReviews(pageable);
    }

    @GetMapping("/{id}")
    public ReviewDto getReview(@PathVariable UUID id) {
        return reviewService.getReview(id);
    }

    @GetMapping("/{id}/job")
    public ReviewJobStatusDto getJobStatus(@PathVariable UUID id) {
        return reviewService.getJobStatus(id);
    }

    @GetMapping("/{id}/findings")
    public PageDto<ReviewFindingDto> getReviewFindings(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return reviewService.getReviewFindings(id, pageable);
    }
}
