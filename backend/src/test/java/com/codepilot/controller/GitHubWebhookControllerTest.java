package com.codepilot.controller;

import com.codepilot.dto.WebhookReceiptDto;
import com.codepilot.model.WebhookDelivery;
import com.codepilot.service.GitHubWebhookService;
import com.codepilot.service.GitHubWebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookControllerTest {

    @Mock
    private GitHubWebhookSignatureVerifier signatureVerifier;

    @Mock
    private GitHubWebhookService webhookService;

    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new GitHubWebhookController(signatureVerifier, webhookService);
        ReflectionTestUtils.setField(controller, "maxPayloadBytes", 1024);
    }

    @Test
    void acceptsVerifiedWebhookAndPersistsReceipt() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setProcessingStatus("RECEIVED");
        when(signatureVerifier.isValid(payload, "valid")).thenReturn(true);
        when(webhookService.receive("delivery-1", "pull_request", payload)).thenReturn(delivery);

        ResponseEntity<WebhookReceiptDto> response = controller.receive(
                "pull_request", "delivery-1", "valid", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(
                new WebhookReceiptDto("delivery-1", "RECEIVED", delivery.getId()));
        verify(webhookService).receive("delivery-1", "pull_request", payload);
    }

    @Test
    void rejectsInvalidSignatureBeforeParsingOrPersistence() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.isValid(payload, "invalid")).thenReturn(false);

        ResponseEntity<WebhookReceiptDto> response = controller.receive(
                "pull_request", "delivery-1", "invalid", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(webhookService, never()).receive(any(), any(), any());
    }

    @Test
    void rejectsOversizedPayload() {
        byte[] payload = new byte[1025];

        ResponseEntity<WebhookReceiptDto> response = controller.receive(
                "pull_request", "delivery-1", "signature", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(signatureVerifier, never()).isValid(any(), eq("signature"));
    }
}
