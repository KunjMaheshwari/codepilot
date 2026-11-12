package com.codepilot.controller;

import com.codepilot.dto.WebhookReceiptDto;
import com.codepilot.model.WebhookDelivery;
import com.codepilot.service.GitHubWebhookService;
import com.codepilot.service.GitHubWebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/webhooks/github")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final GitHubWebhookSignatureVerifier signatureVerifier;
    private final GitHubWebhookService webhookService;

    @Value("${codepilot.webhook.max-payload-bytes:1048576}")
    private int maxPayloadBytes;

    @PostMapping
    public ResponseEntity<WebhookReceiptDto> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] payload) {
        if (eventType == null || eventType.isBlank() || deliveryId == null || deliveryId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (payload == null || payload.length > maxPayloadBytes) {
            return ResponseEntity.badRequest().build();
        }
        if (!signatureVerifier.isValid(payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            WebhookDelivery delivery = webhookService.receive(deliveryId, eventType, payload);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new WebhookReceiptDto(deliveryId, delivery.getProcessingStatus(), delivery.getId()));
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new WebhookReceiptDto(deliveryId, "RECEIVED", null));
        }
    }
}
