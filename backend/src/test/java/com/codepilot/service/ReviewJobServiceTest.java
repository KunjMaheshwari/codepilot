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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void terminalJobCannotTransitionBackToActiveStates() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob completed = new ReviewJob();
        completed.setStatus("COMPLETED");

        assertThatThrownBy(() -> service.updateStatus(completed, "PROCESSING"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.markPending(completed))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.markQueued(completed))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.markRetrying(completed, "retry"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedRecoverableJobCanTransitionToRetryingAndIdempotentlyStayRetrying() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob job = new ReviewJob();
        job.setStatus("FAILED");
        job.setLastErrorCategory("GEMINI_API_TRANSIENT");

        service.markRetrying(job, "Transient 503");
        assertThat(job.getStatus()).isEqualTo("RETRYING");
        assertThat(job.getCompletedAt()).isNull();

        service.markRetrying(job, "Transient 503");
        assertThat(job.getStatus()).isEqualTo("RETRYING");
    }

    @Test
    void permanentFailureCannotTransitionToRetrying() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob job = new ReviewJob();
        job.setStatus("FAILED");
        job.setLastErrorCategory("NO_REVIEWABLE_FILES");

        assertThat(service.isPermanentFailure(job)).isTrue();
        assertThatThrownBy(() -> service.markRetrying(job, "No supported source files"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot retry permanent review job failure");
    }

    @Test
    void retryExhaustionMarksJobFailedWithRetryExhaustedCategory() {
        ReviewJobService service = new ReviewJobService(repository);
        ReviewJob job = new ReviewJob();
        job.setStatus("FAILED");
        job.setLastErrorCategory("GEMINI_API_TRANSIENT");

        service.markRetryExhausted(job, "Gemini timeout");

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getLastErrorCategory()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(job.getErrorMessage()).contains("Retries exhausted: Gemini timeout");
        verify(repository).save(job);
    }
}
