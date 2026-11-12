package com.codepilot.service;

import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewJobProducerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ReviewJobService reviewJobService;

    private ReviewJobProducer producer;
    private ReviewJob job;

    @BeforeEach
    void setUp() {
        producer = new ReviewJobProducer(redisTemplate, new ObjectMapper(), reviewJobService);
        ReflectionTestUtils.setField(producer, "queue", "review-queue");

        User user = new User();
        user.setId(UUID.randomUUID());
        Repository repository = new Repository();
        repository.setId(UUID.randomUUID());
        repository.setOwner(user);
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(UUID.randomUUID());
        pullRequest.setRepository(repository);
        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setPullRequest(pullRequest);
        job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setReview(review);
        job.setCorrelationId("correlation");
    }

    @Test
    void publishesOnlyIdentifiersAndMarksJobQueued() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        producer.publish(job);

        verify(listOperations).leftPush(eq("review-queue"), anyString());
        verify(reviewJobService).markQueued(job);
    }

    @Test
    void retainsPendingStateWhenRedisPublicationFails() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(listOperations).leftPush(eq("review-queue"), anyString());

        producer.publish(job);

        verify(reviewJobService).markPending(job);
        assertThat(job.getStatus()).isNotEqualTo("FAILED");
    }
}
