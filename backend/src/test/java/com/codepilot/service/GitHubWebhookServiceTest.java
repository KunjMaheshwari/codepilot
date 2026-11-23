package com.codepilot.service;

import com.codepilot.model.WebhookDelivery;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.WebhookDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookServiceTest {

    @Mock
    private WebhookDeliveryRepository deliveryRepository;
    @Mock
    private WebhookDeliveryStateService deliveryStateService;
    @Mock
    private RepositoryRepository repositoryRepository;
    @Mock
    private GitHubIntegrationService gitHubIntegrationService;
    @Mock
    private ReviewService reviewService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GitHubWebhookService webhookService;

    @Test
    void receiveReturnsExistingDeliveryWithoutSavingDuplicate() {
        WebhookDelivery existing = new WebhookDelivery();
        existing.setDeliveryId("del-1");
        existing.setProcessingStatus("PROCESSED");
        when(deliveryRepository.findByDeliveryId("del-1")).thenReturn(Optional.of(existing));

        WebhookDelivery result = webhookService.receive(
                "del-1", "pull_request", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(result).isSameAs(existing);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void receivePersistsNewDeliveryMetadata() {
        when(deliveryRepository.findByDeliveryId("del-2")).thenReturn(Optional.empty());
        when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] payload = """
                {"action":"opened","number":42,"repository":{"id":999}}
                """.getBytes(StandardCharsets.UTF_8);

        WebhookDelivery saved = webhookService.receive("del-2", "pull_request", payload);

        assertThat(saved.getDeliveryId()).isEqualTo("del-2");
        assertThat(saved.getEventType()).isEqualTo("pull_request");
        assertThat(saved.getAction()).isEqualTo("opened");
        assertThat(saved.getRepositoryGithubId()).isEqualTo("999");
        assertThat(saved.getPullRequestNumber()).isEqualTo(42);
        assertThat(saved.getProcessingStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void receiveRejectsInvalidOrNonObjectJsonPayload() {
        when(deliveryRepository.findByDeliveryId("del-bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.receive(
                "del-bad", "pull_request", "{invalid".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Webhook payload is not valid JSON");

        assertThatThrownBy(() -> webhookService.receive(
                "del-bad", "pull_request", "[1,2,3]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Webhook payload is not a JSON object");
    }
}
