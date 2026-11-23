package com.codepilot.service;

import com.codepilot.dto.PageDto;
import com.codepilot.dto.PullRequestDto;
import com.codepilot.exception.ResourceNotFoundException;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.PullRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PullRequestServiceTest {

    @Mock
    private PullRequestRepository pullRequestRepository;
    @Mock
    private RepositoryService repositoryService;

    @InjectMocks
    private PullRequestService pullRequestService;

    @Test
    void getPullRequestsScopesByAuthenticatedOwner() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);

        Repository repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setOwner(owner);
        PullRequest pr = new PullRequest();
        pr.setId(UUID.randomUUID());
        pr.setRepository(repo);
        pr.setPrNumber(12);
        pr.setTitle("Fix parser");
        pr.setStatus("open");

        PageRequest pageable = PageRequest.of(0, 20);
        when(pullRequestRepository.findByRepositoryOwnerId(owner.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(pr), pageable, 1));

        PageDto<PullRequestDto> result = pullRequestService.getPullRequests(pageable);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(pr.getId());
            assertThat(dto.getPrNumber()).isEqualTo(12);
        });
    }

    @Test
    void getPullRequestThrowsNotFoundForOtherTenant() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        UUID prId = UUID.randomUUID();
        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(pullRequestRepository.findByIdAndRepositoryOwnerId(prId, owner.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> pullRequestService.getPullRequest(prId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPullRequestsForRepositoryVerifiesOwnershipFirst() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        UUID repoId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);

        when(repositoryService.getAuthenticatedUser()).thenReturn(owner);
        when(pullRequestRepository.findByRepositoryIdAndRepositoryOwnerId(repoId, owner.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageDto<PullRequestDto> page = pullRequestService.getPullRequestsForRepository(repoId, pageable);

        assertThat(page.getContent()).isEmpty();
        verify(repositoryService).getOwnedRepository(repoId);
    }
}
