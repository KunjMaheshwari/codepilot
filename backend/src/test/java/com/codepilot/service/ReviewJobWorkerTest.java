package com.codepilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ReviewJobWorkerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;
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
        ReflectionTestUtils.setField(worker, "deadLetterQueue", "dead-letter");
        when(redisTemplate.opsForList()).thenReturn(listOperations);
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
    }
}
