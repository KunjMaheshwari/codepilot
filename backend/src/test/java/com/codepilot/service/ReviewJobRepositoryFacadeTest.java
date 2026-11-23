package com.codepilot.service;

import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewJobRepositoryFacadeTest {

    @Mock
    private ReviewJobRepository repository;

    @InjectMocks
    private ReviewJobRepositoryFacade facade;

    @Test
    void findDelegatesToFindWithReviewByIdAndReturnsNullWhenAbsent() {
        UUID id = UUID.randomUUID();
        ReviewJob job = new ReviewJob();
        job.setId(id);

        when(repository.findWithReviewById(id)).thenReturn(Optional.of(job));
        assertThat(facade.find(id)).isSameAs(job);

        UUID missing = UUID.randomUUID();
        when(repository.findWithReviewById(missing)).thenReturn(Optional.empty());
        assertThat(facade.find(missing)).isNull();

        verify(repository).findWithReviewById(id);
        verify(repository).findWithReviewById(missing);
    }
}
