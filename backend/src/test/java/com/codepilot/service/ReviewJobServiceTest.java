package com.codepilot.service;

import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewJobServiceTest {

    @Mock
    private ReviewJobRepository repository;

    @Test
    void staleProcessingJobsAreSelectedByHeartbeatTimeout() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob job = new ReviewJob();
        when(repository.findStaleProcessingJobs(
                org.mockito.ArgumentMatchers.eq("PROCESSING"),
                org.mockito.ArgumentMatchers.any(ZonedDateTime.class)))
                .thenReturn(List.of(job));

        assertThat(service.findStaleProcessingJobs(Duration.ofMinutes(15))).containsExactly(job);
    }

    @Test
    void staleJobAtMaximumAttemptsBecomesTerminalFailure() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob job = new ReviewJob();
        job.setStatus("PROCESSING");
        job.setAttemptCount(3);

        service.recoverStaleJob(job, 3);

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getLastErrorCategory()).isEqualTo("TIMEOUT");
        verify(repository).save(job);
    }

    @Test
    void staleJobBelowMaximumAttemptsBecomesRetryable() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob job = new ReviewJob();
        job.setStatus("PROCESSING");
        job.setAttemptCount(1);

        service.recoverStaleJob(job, 3);

        assertThat(job.getStatus()).isEqualTo("RETRYING");
        assertThat(job.getLastErrorCategory()).isEqualTo("TIMEOUT");
        verify(repository).save(job);
    }
}
