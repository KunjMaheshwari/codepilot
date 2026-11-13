package com.codepilot.service;

import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewJobWorkerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private ReviewJobService reviewJobService;
    @Mock
    private CodeReviewOrchestrator orchestrator;
    @Mock
    private ReviewJobRepositoryFacade jobRepository;

    private ReviewJobWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ReviewJobWorker(
                redisTemplate, new ObjectMapper(), reviewJobService, orchestrator, jobRepository);
        ReflectionTestUtils.setField(worker, "enabled", true);
        ReflectionTestUtils.setField(worker, "queue", "queue");
        ReflectionTestUtils.setField(worker, "processingQueue", "processing");
        ReflectionTestUtils.setField(worker, "retryQueue", "retry");
        ReflectionTestUtils.setField(worker, "deadLetterQueue", "dead-letter");
        ReflectionTestUtils.setField(worker, "maxAttempts", 3);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void movesOneMessageFromMainQueueToProcessingAndAcknowledgesDuplicate() {
        String payload = """
                {"jobId":"00000000-0000-0000-0000-000000000001",
                 "reviewId":"00000000-0000-0000-0000-000000000002",
                 "pullRequestId":"00000000-0000-0000-0000-000000000003",
                 "repositoryId":"00000000-0000-0000-0000-000000000004",
                 "createdAt":null,
                 "correlationId":"c"}
                """;
        when(listOperations.rightPopAndLeftPush("queue", "processing")).thenReturn(payload);
        when(reviewJobService.claim(java.util.UUID.fromString(
                "00000000-0000-0000-0000-000000000001"))).thenReturn(false);

        worker.poll();

        verify(listOperations).rightPopAndLeftPush("queue", "processing");
        verify(listOperations, never()).rightPopAndLeftPush("processing", "queue");
        verify(listOperations).remove("processing", 1, payload);
        verify(orchestrator, never()).orchestrateReview(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void movesMalformedMessagesToDeadLetter() {
        when(listOperations.rightPopAndLeftPush("queue", "processing")).thenReturn("not-json");
        when(listOperations.remove("processing", 1, "not-json")).thenReturn(1L);

        worker.poll();

        verify(listOperations).remove("processing", 1, "not-json");
        verify(listOperations).leftPush("dead-letter", "not-json");
        assertThat(worker.getDeadLetterCount()).isEqualTo(1);
    }

    @Test
    void schedulesNonBlockingDelayedRetryInRedisZSetBeforeAcknowledging() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reviewId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String payload = """
                {"jobId":"00000000-0000-0000-0000-000000000001",
                 "reviewId":"00000000-0000-0000-0000-000000000002",
                 "pullRequestId":"00000000-0000-0000-0000-000000000003",
                 "repositoryId":"00000000-0000-0000-0000-000000000004",
                 "createdAt":null,
                 "correlationId":"c"}
                """;
        Review review = new Review();
        review.setId(reviewId);
        ReviewJob job = new ReviewJob();
        job.setId(jobId);
        job.setReview(review);
        job.setStatus("FAILED");
        job.setAttemptCount(1);
        job.setErrorMessage("Transient Gemini 503");

        when(listOperations.rightPopAndLeftPush("queue", "processing")).thenReturn(payload);
        when(reviewJobService.claim(jobId)).thenReturn(true);
        when(jobRepository.find(jobId)).thenReturn(job);

        worker.poll();

        verify(reviewJobService).markRetrying(job, "Transient Gemini 503");
        verify(zSetOperations).add(eq("retry"), eq(payload), anyDouble());
        verify(listOperations).remove("processing", 1, payload);
        assertThat(worker.getRetryScheduledCount()).isEqualTo(1);
    }

    @Test
    void movesExhaustedJobToDeadLetterQueue() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reviewId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String payload = """
                {"jobId":"00000000-0000-0000-0000-000000000001",
                 "reviewId":"00000000-0000-0000-0000-000000000002",
                 "pullRequestId":"00000000-0000-0000-0000-000000000003",
                 "repositoryId":"00000000-0000-0000-0000-000000000004",
                 "createdAt":null,
                 "correlationId":"c"}
                """;
        Review review = new Review();
        review.setId(reviewId);
        ReviewJob job = new ReviewJob();
        job.setId(jobId);
        job.setReview(review);
        job.setStatus("FAILED");
        job.setAttemptCount(3);
        job.setErrorMessage("Exhausted");

        when(listOperations.rightPopAndLeftPush("queue", "processing")).thenReturn(payload);
        when(reviewJobService.claim(jobId)).thenReturn(true);
        when(jobRepository.find(jobId)).thenReturn(job);

        worker.poll();

        verify(listOperations).leftPush("dead-letter", payload);
        verify(listOperations).remove("processing", 1, payload);
        assertThat(worker.getDeadLetterCount()).isEqualTo(1);
    }

    @Test
    void promotesDueRetriesToMainQueue() {
        when(zSetOperations.rangeByScore(eq("retry"), eq(0.0), anyDouble(), eq(0L), eq(50L)))
                .thenReturn(Set.of("msg-1"));
        when(zSetOperations.remove("retry", "msg-1")).thenReturn(1L);

        worker.promoteDueRetries();

        verify(listOperations).leftPush("queue", "msg-1");
    }

    @Test
    void permanentFailureAcknowledgesWithoutRetryingOrDeadLettering() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reviewId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String payload = """
                {"jobId":"00000000-0000-0000-0000-000000000001",
                 "reviewId":"00000000-0000-0000-0000-000000000002",
                 "pullRequestId":"00000000-0000-0000-0000-000000000003",
                 "repositoryId":"00000000-0000-0000-0000-000000000004",
                 "createdAt":null,
                 "correlationId":"c"}
                """;
        Review review = new Review();
        review.setId(reviewId);
        ReviewJob job = new ReviewJob();
        job.setId(jobId);
        job.setReview(review);
        job.setStatus("FAILED");
        job.setAttemptCount(1);
        job.setLastErrorCategory("NO_REVIEWABLE_FILES");
        job.setErrorMessage("Pull request diff has no supported source files");

        when(listOperations.rightPopAndLeftPush("queue", "processing")).thenReturn(payload);
        when(reviewJobService.claim(jobId)).thenReturn(true);
        when(jobRepository.find(jobId)).thenReturn(job);
        when(reviewJobService.isPermanentFailure(job)).thenReturn(true);

        worker.poll();

        verify(reviewJobService, never()).markRetrying(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(zSetOperations, never()).add(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), anyDouble());
        verify(listOperations, never()).leftPush(eq("dead-letter"), org.mockito.ArgumentMatchers.anyString());
        verify(listOperations).remove("processing", 1, payload);
    }
}
