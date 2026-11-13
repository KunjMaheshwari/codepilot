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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
@Slf4j
public class ReviewJobWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewJobService reviewJobService;
    private final CodeReviewOrchestrator orchestrator;
    private final ReviewJobRepositoryFacade jobRepository;
    private final Set<UUID> activeJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong retryScheduledCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();

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

    @Value("${codepilot.review.async.retry-queue:codepilot:review-jobs:retry}")
    private String retryQueue = "codepilot:review-jobs:retry";

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
            activeJobIds.add(job.getId());
            try {
                orchestrator.orchestrateReview(job);
            } finally {
                activeJobIds.remove(job.getId());
            }
            if ("FAILED".equals(job.getStatus())) {
                if (reviewJobService.isPermanentFailure(job)) {
                    log.warn("Permanent review failure jobId={} correlationId={} category={}; not retrying",
                            job.getId(), job.getCorrelationId(), job.getLastErrorCategory());
                } else if (job.getAttemptCount() < maxAttempts) {
                    reviewJobService.markRetrying(job, job.getErrorMessage());
                    long delayMs = computeRetryDelayMillis(job.getAttemptCount(), job.getRetryAfterSeconds());
                    long executeAt = System.currentTimeMillis() + delayMs;
                    redisTemplate.opsForZSet().add(retryQueue, payload, executeAt);
                    retryScheduledCount.incrementAndGet();
                    log.warn("Scheduled delayed retry jobId={} correlationId={} attempt={} delayMs={}",
                            job.getId(), job.getCorrelationId(), job.getAttemptCount(), delayMs);
                } else {
                    reviewJobService.markRetryExhausted(job, job.getErrorMessage());
                    redisTemplate.opsForList().leftPush(deadLetterQueue, payload);
                    deadLetterCount.incrementAndGet();
                    log.error("Exhausted review job moved to dead-letter queue jobId={} correlationId={} attempt={}",
                            job.getId(), job.getCorrelationId(), job.getAttemptCount());
                }
            } else {
                processedCount.incrementAndGet();
            }
            acknowledge(payload);
        } catch (JsonProcessingException exception) {
            moveToDeadLetter(payload);
            deadLetterCount.incrementAndGet();
            log.error("Moved malformed review job message to dead-letter queue");
        } catch (Exception exception) {
            log.error("Review worker failed for job payload; leaving it in processing queue", exception);
        }
    }

    @Scheduled(fixedDelayString = "${codepilot.review.async.worker-delay-ms:1000}")
    public void promoteDueRetries() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<String> duePayloads = redisTemplate.opsForZSet().rangeByScore(retryQueue, 0, now, 0, 50);
        if (duePayloads == null || duePayloads.isEmpty()) {
            return;
        }
        for (String payload : duePayloads) {
            Long removed = redisTemplate.opsForZSet().remove(retryQueue, payload);
            if (removed != null && removed > 0) {
                redisTemplate.opsForList().leftPush(queue, payload);
                log.info("Promoted due retry message to main review queue");
            }
        }
    }

    long computeRetryDelayMillis(int attemptCount) {
        return computeRetryDelayMillis(attemptCount, null);
    }

    long computeRetryDelayMillis(int attemptCount, Long retryAfterSeconds) {
        long base = Math.min(1000L * (1L << Math.min(Math.max(attemptCount, 1), 5)), 30000L);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, base / 4));
        long backoff = Math.min(base + jitter, 35000L);
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return Math.min(Math.max(backoff, retryAfterSeconds * 1000L), 60000L);
        }
        return backoff;
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getRetryScheduledCount() {
        return retryScheduledCount.get();
    }

    public long getDeadLetterCount() {
        return deadLetterCount.get();
    }

    @Scheduled(fixedDelayString = "${codepilot.review.async.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        if (enabled && !activeJobIds.isEmpty()) {
            reviewJobService.touchProcessingJobs(activeJobIds);
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
