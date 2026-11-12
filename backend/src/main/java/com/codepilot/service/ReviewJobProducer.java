package com.codepilot.service;

import com.codepilot.model.ReviewJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;

@Service
@Slf4j
public class ReviewJobProducer {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewJobService reviewJobService;

    public ReviewJobProducer(
            @Qualifier("reviewRedisTemplate") RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            ReviewJobService reviewJobService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.reviewJobService = reviewJobService;
    }

    @Value("${codepilot.review.async.queue:codepilot:review-jobs}")
    private String queue;

    @Value("${codepilot.review.async.enabled:true}")
    private boolean enabled;

    @Value("${codepilot.review.async.queued-recovery-seconds:60}")
    private long queuedRecoverySeconds = 60;

    public void publish(ReviewJob job) {
        ReviewJobMessage message = new ReviewJobMessage(
                job.getId(),
                job.getReview().getId(),
                job.getReview().getPullRequest().getId(),
                job.getReview().getPullRequest().getRepository().getId(),
                job.getCreatedAt(),
                job.getCorrelationId());
        try {
            redisTemplate.opsForList().leftPush(queue, objectMapper.writeValueAsString(message));
            reviewJobService.markQueued(job);
            log.info("Queued review job {} correlationId={}", job.getId(), job.getCorrelationId());
        } catch (JsonProcessingException | RuntimeException exception) {
            reviewJobService.markPending(job);
            log.error("Failed to publish review job {} correlationId={}", job.getId(), job.getCorrelationId());
        }
    }

    public void recoverPendingJobs() {
        if (!enabled) {
            return;
        }
        for (ReviewJob job : reviewJobService.findPendingJobs()) {
            try {
                publish(job);
            } catch (RuntimeException ignored) {
                log.warn("Pending review job {} remains unpublished", job.getId());
            }
        }
        for (ReviewJob staleQueued : reviewJobService.findStaleQueuedJobs(Duration.ofSeconds(queuedRecoverySeconds))) {
            try {
                publish(staleQueued);
            } catch (RuntimeException ignored) {
                log.warn("Stale queued review job {} remains unpublished", staleQueued.getId());
            }
        }
    }

    @Scheduled(fixedDelayString = "${codepilot.review.async.worker-delay-ms:1000}")
    public void recoverPendingJobsOnSchedule() {
        recoverPendingJobs();
    }
}
