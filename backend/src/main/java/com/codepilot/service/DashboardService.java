package com.codepilot.service;

import com.codepilot.dto.DashboardStatsDto;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ReviewRepository reviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryService repositoryService;

    @Transactional(readOnly = true)
    public DashboardStatsDto getDashboardStats() {
        var userId = repositoryService.getAuthenticatedUser().getId();
        return DashboardStatsDto.builder()
                .totalReviews(reviewRepository.countByPullRequestRepositoryOwnerId(userId))
                .issuesDetected(reviewFindingRepository.countByReviewPullRequestRepositoryOwnerId(userId))
                .criticalIssues(reviewFindingRepository.countByReviewPullRequestRepositoryOwnerIdAndSeverity(userId, "CRITICAL"))
                .activeRepositories(repositoryRepository.countByOwnerIdAndIsActiveTrue(userId))
                .build();
    }
}
