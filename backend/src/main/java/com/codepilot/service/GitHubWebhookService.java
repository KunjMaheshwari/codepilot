package com.codepilot.service;

import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.model.WebhookDelivery;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.WebhookDeliveryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookService {

    private static final List<String> REVIEW_ACTIONS = List.of("opened", "synchronize", "reopened");
    private static final String PULL_REQUEST_EVENT = "pull_request";

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryStateService deliveryStateService;
    private final RepositoryRepository repositoryRepository;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @Value("${codepilot.webhook.max-attempts:5}")
    private int maxAttempts;

    @Value("${codepilot.webhook.retry-delay-seconds:30}")
    private long retryDelaySeconds;

    @Transactional
    public WebhookDelivery receive(String deliveryId, String eventType, byte[] payload) {
        java.util.Optional<WebhookDelivery> existing = deliveryRepository.findByDeliveryId(deliveryId);
        if (existing.isPresent()) {
            return existing.get();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Webhook payload is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Webhook payload is not a JSON object");
        }

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(deliveryId);
        delivery.setEventType(eventType);
        delivery.setAction(text(root, "action"));
        JsonNode repository = root.path("repository");
        JsonNode repositoryId = repository.path("id");
        delivery.setRepositoryGithubId(repositoryId.isNumber() ? repositoryId.asText() : null);
        JsonNode pullRequest = root.path("number");
        delivery.setPullRequestNumber(pullRequest.isIntegralNumber() && pullRequest.asInt() > 0
                ? pullRequest.asInt() : null);
        delivery.setProcessingStatus("RECEIVED");
        delivery.setAttemptCount(0);
        delivery.setReceivedAt(ZonedDateTime.now());
        delivery.setNextAttemptAt(ZonedDateTime.now());
        return deliveryRepository.save(delivery);
    }

    @Scheduled(fixedDelayString = "${codepilot.webhook.worker-delay-ms:1000}")
    public void processPendingEvents() {
        List<WebhookDelivery> candidates = deliveryStateService.findPendingDeliveries();
        for (WebhookDelivery candidate : candidates) {
            deliveryStateService.claim(candidate.getId(), maxAttempts).ifPresent(this::process);
        }
    }

    private void process(WebhookDelivery delivery) {
        try {
            if (!PULL_REQUEST_EVENT.equals(delivery.getEventType())) {
                deliveryStateService.markIgnored(delivery, "UNSUPPORTED_EVENT", "Unsupported webhook event");
                return;
            }
            if (!REVIEW_ACTIONS.contains(delivery.getAction())
                    && !"closed".equals(delivery.getAction())) {
                deliveryStateService.markIgnored(delivery, "UNSUPPORTED_ACTION", "Unsupported pull request action");
                return;
            }
            if (delivery.getRepositoryGithubId() == null || delivery.getPullRequestNumber() == null) {
                throw new IllegalArgumentException("Webhook payload is missing repository or pull request identity");
            }

            Repository repository = repositoryRepository
                    .findWithOwnerByGithubRepoId(delivery.getRepositoryGithubId())
                    .orElse(null);
            if (repository == null || repository.getOwner() == null) {
                deliveryStateService.markIgnored(delivery, "UNLINKED_REPOSITORY", "Repository is not linked to CodePilot");
                return;
            }

            User owner = repository.getOwner();
            java.util.Map<String, Object> remotePullRequest = gitHubIntegrationService.getPullRequestDetails(
                    owner, repository, delivery.getPullRequestNumber());
            PullRequest pullRequest = gitHubIntegrationService.persistSynchronizedPullRequest(
                    repository, remotePullRequest);
            if ("closed".equals(delivery.getAction())) {
                deliveryStateService.markProcessed(delivery);
                return;
            }
            if (pullRequest.getHeadSha() == null || pullRequest.getHeadSha().isBlank()) {
                throw new IllegalStateException("Pull request head revision is unavailable");
            }

            reviewService.createReviewForWebhook(owner, repository, pullRequest);
            deliveryStateService.markProcessed(delivery);
        } catch (Exception exception) {
            deliveryStateService.markFailed(delivery, exception, maxAttempts, retryDelaySeconds);
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
