package com.codepilot.service;

import com.codepilot.dto.DashboardStatsDto;
import com.codepilot.model.User;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.ReviewFindingRepository;
import com.codepilot.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceSecurityTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewFindingRepository reviewFindingRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private RepositoryService repositoryService;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void shouldOnlyReadCountsForAuthenticatedUser() {
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);
        when(repositoryService.getAuthenticatedUser()).thenReturn(user);
        when(reviewRepository.countByPullRequestRepositoryOwnerId(userId)).thenReturn(2L);
        when(reviewFindingRepository.countByReviewPullRequestRepositoryOwnerId(userId)).thenReturn(3L);
        when(reviewFindingRepository.countByReviewPullRequestRepositoryOwnerIdAndSeverity(userId, "CRITICAL"))
                .thenReturn(1L);
        when(repositoryRepository.countByOwnerIdAndIsActiveTrue(userId)).thenReturn(1L);

        DashboardStatsDto stats = dashboardService.getDashboardStats();

        assertThat(stats.getTotalReviews()).isEqualTo(2L);
        assertThat(stats.getIssuesDetected()).isEqualTo(3L);
        assertThat(stats.getCriticalIssues()).isEqualTo(1L);
        assertThat(stats.getActiveRepositories()).isEqualTo(1L);
        verify(reviewRepository).countByPullRequestRepositoryOwnerId(userId);
        verify(reviewFindingRepository).countByReviewPullRequestRepositoryOwnerId(userId);
        verify(repositoryRepository).countByOwnerIdAndIsActiveTrue(userId);
    }
}
