package com.codepilot.service;

import com.codepilot.model.WebhookDelivery;
import com.codepilot.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryStateService {

    private final WebhookDeliveryRepository deliveryRepository;

    @Transactional(readOnly = true)
    public List<WebhookDelivery> findPendingDeliveries() {
        return deliveryRepository.findTop50ByProcessingStatusInAndNextAttemptAtLessThanEqualOrderByReceivedAtAsc(
                List.of("RECEIVED", "FAILED"), ZonedDateTime.now());
    }

    @Transactional
    public Optional<WebhookDelivery> claim(UUID deliveryId, int maxAttempts) {
        if (deliveryRepository.claim(deliveryId, maxAttempts) != 1) {
            return Optional.empty();
        }
        return deliveryRepository.findById(deliveryId);
    }

    @Transactional
    public void markProcessed(WebhookDelivery delivery) {
        delivery.setProcessingStatus("PROCESSED");
        delivery.setProcessedAt(ZonedDateTime.now());
        delivery.setNextAttemptAt(null);
        deliveryRepository.save(delivery);
        log.info("Processed webhook deliveryId={} eventType={} action={} attempt={}",
                delivery.getDeliveryId(), delivery.getEventType(),
                delivery.getAction(), delivery.getAttemptCount());
    }

    @Transactional
    public void markIgnored(WebhookDelivery delivery, String category, String message) {
        delivery.setProcessingStatus("IGNORED");
        delivery.setErrorCategory(category);
        delivery.setSafeErrorMessage(message);
        delivery.setProcessedAt(ZonedDateTime.now());
        delivery.setNextAttemptAt(null);
        deliveryRepository.save(delivery);
        log.info("Ignored webhook deliveryId={} eventType={} action={} category={} attempt={}",
                delivery.getDeliveryId(), delivery.getEventType(),
                delivery.getAction(), category, delivery.getAttemptCount());
    }

    @Transactional
    public void markFailed(WebhookDelivery delivery, Exception exception, int maxAttempts, long retryDelaySeconds) {
        delivery.setProcessingStatus("FAILED");
        delivery.setErrorCategory("PROCESSING");
        delivery.setSafeErrorMessage(safeMessage(exception));
        delivery.setNextAttemptAt(delivery.getAttemptCount() < maxAttempts
                ? ZonedDateTime.now().plusSeconds(retryDelaySeconds)
                : null);
        deliveryRepository.save(delivery);
        log.warn("Webhook processing failed deliveryId={} eventType={} action={} attempt={} retryScheduled={}",
                delivery.getDeliveryId(), delivery.getEventType(), delivery.getAction(),
                delivery.getAttemptCount(), delivery.getNextAttemptAt() != null);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Webhook processing failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
