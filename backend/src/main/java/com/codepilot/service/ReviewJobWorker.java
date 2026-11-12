package com.codepilot.service;

import com.codepilot.model.ReviewJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
@Slf4j
public class ReviewJobWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewJobService reviewJobService;
    private final CodeReviewOrchestrator orchestrator;
    private final ReviewJobRepositoryFacade jobRepository;

    public ReviewJobWorker(
            @Qualifier("reviewRedisTemplate") RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            ReviewJobService reviewJobService,
            CodeReviewOrchestrator orchestrator,
            ReviewJobRepositoryFacade jobRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.reviewJobService = reviewJobService;
        this.orchestrator = orchestrator;
        this.jobRepository = jobRepository;
    }

    @Value("${codepilot.review.async.queue:codepilot:review-jobs}")
    private String queue;

    @Value("${codepilot.review.async.processing-queue:codepilot:review-jobs:processing}")
    private String processingQueue;

    @Value("${codepilot.review.async.dead-letter-queue:codepilot:review-jobs:dead-letter}")
    private String deadLetterQueue;

    @Value("${codepilot.review.async.max-attempts:3}")
    private int maxAttempts;

    @Value("${codepilot.review.async.enabled:true}")
    private boolean enabled;

    @Value("${codepilot.review.async.processing-timeout-seconds:900}")
    private long processingTimeoutSeconds;

    @Scheduled(fixedDelayString = "${codepilot.review.async.worker-delay-ms:1000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        String payload = redisTemplate.opsForList().rightPopAndLeftPush(queue, processingQueue);
        if (payload == null) {
            return;
        }
        try {
            ReviewJobMessage message = objectMapper.readValue(payload, ReviewJobMessage.class);
            log.info("Processing review job {} correlationId={}",
                    message.jobId(), message.correlationId());
            if (!reviewJobService.claim(message.jobId())) {
                log.info("Ignoring duplicate or already claimed review job {} correlationId={}",
                        message.jobId(), message.correlationId());
                acknowledge(payload);
                return;
            }
            ReviewJob job = jobRepository.find(message.jobId());
            if (job == null || !job.getReview().getId().equals(message.reviewId())) {
                log.error("Rejecting invalid review job message {} correlationId={}",
                        message.jobId(), message.correlationId());
                acknowledge(payload);
                return;
            }
            orchestrator.orchestrateReview(job);
            if ("FAILED".equals(job.getStatus()) && job.getAttemptCount() < maxAttempts) {
                reviewJobService.markRetrying(job, job.getErrorMessage());
                Thread.sleep(Math.min(1000L * (1L << Math.min(job.getAttemptCount(), 5)), 30000L));
                redisTemplate.opsForList().leftPush(queue, payload);
            } else if ("FAILED".equals(job.getStatus())) {
                redisTemplate.opsForList().leftPush(deadLetterQueue, payload);
            }
            acknowledge(payload);
        } catch (JsonProcessingException exception) {
            moveToDeadLetter(payload);
            log.error("Moved malformed review job message to dead-letter queue");
        } catch (Exception exception) {
            log.error("Review worker failed for job payload; leaving it in processing queue", exception);
        }
    }

    @Scheduled(fixedDelayString = "${codepilot.review.async.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        if (enabled) {
            reviewJobService.touchProcessingJobs();
        }
    }

    @Scheduled(fixedDelayString = "${codepilot.review.async.worker-delay-ms:1000}")
    public void recoverStaleJobs() {
        if (!enabled) {
            return;
        }
        List<ReviewJob> staleJobs = reviewJobService.findStaleProcessingJobs(
                Duration.ofSeconds(processingTimeoutSeconds));
        for (ReviewJob job : staleJobs) {
            reviewJobService.recoverStaleJob(job, maxAttempts);
            if ("RETRYING".equals(job.getStatus())) {
                if (!requeueStaleMessage(job.getId())) {
                    reviewJobService.markPending(job);
                }
            }
        }
    }

    private boolean requeueStaleMessage(java.util.UUID jobId) {
        List<String> messages = redisTemplate.opsForList().range(processingQueue, 0, -1);
        if (messages == null) {
            return false;
        }
        for (String message : messages) {
            try {
                ReviewJobMessage candidate = objectMapper.readValue(message, ReviewJobMessage.class);
                if (jobId.equals(candidate.jobId())
                        && redisTemplate.opsForList().remove(processingQueue, 1, message) > 0) {
                    redisTemplate.opsForList().leftPush(queue, message);
                    return true;
                }
            } catch (Exception exception) {
                log.warn("Unable to inspect processing queue message for stale recovery");
            }
        }
        return false;
    }

    private void acknowledge(String payload) {
        redisTemplate.opsForList().remove(processingQueue, 1, payload);
    }

    private void moveToDeadLetter(String payload) {
        if (redisTemplate.opsForList().remove(processingQueue, 1, payload) > 0) {
            redisTemplate.opsForList().leftPush(deadLetterQueue, payload);
        }
    }
}
