package com.codepilot.service;

import com.codepilot.dto.DashboardStatsDto;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ReviewRepository reviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final RepositoryRepository repositoryRepository;

    public DashboardStatsDto getDashboardStats() {
        return DashboardStatsDto.builder()
                .totalReviews(reviewRepository.count())
                .issuesDetected(reviewFindingRepository.count())
                .criticalIssues(0L) // Simplified for the foundation
                .activeRepositories(repositoryRepository.count())
                .build();
    }
}
