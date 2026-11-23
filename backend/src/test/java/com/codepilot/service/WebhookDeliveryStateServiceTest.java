package com.codepilot.service;

import com.codepilot.model.WebhookDelivery;
import com.codepilot.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryStateServiceTest {

    @Mock
    private WebhookDeliveryRepository deliveryRepository;

    @InjectMocks
    private WebhookDeliveryStateService stateService;

    @Test
    void claimReturnsEmptyWhenUpdateAffectsZeroRows() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.claim(id, 5)).thenReturn(0);

        assertThat(stateService.claim(id, 5)).isEmpty();
    }

    @Test
    void claimReturnsEntityWhenRowClaimed() {
        UUID id = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(id);
        when(deliveryRepository.claim(id, 5)).thenReturn(1);
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(delivery));

        assertThat(stateService.claim(id, 5)).contains(delivery);
    }

    @Test
    void markFailedSchedulesRetryUntilMaxAttemptsReached() {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId("d-1");
        delivery.setAttemptCount(2);

        stateService.markFailed(delivery, new RuntimeException("Temporary error"), 5, 30);

        assertThat(delivery.getProcessingStatus()).isEqualTo("FAILED");
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        verify(deliveryRepository).save(delivery);

        delivery.setAttemptCount(5);
        stateService.markFailed(delivery, new RuntimeException("Final error"), 5, 30);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void markProcessedAndIgnoredClearNextAttemptAt() {
        WebhookDelivery processed = new WebhookDelivery();
        processed.setDeliveryId("d-2");
        stateService.markProcessed(processed);
        assertThat(processed.getProcessingStatus()).isEqualTo("PROCESSED");
        assertThat(processed.getProcessedAt()).isNotNull();
        assertThat(processed.getNextAttemptAt()).isNull();

        WebhookDelivery ignored = new WebhookDelivery();
        ignored.setDeliveryId("d-3");
        stateService.markIgnored(ignored, "UNSUPPORTED_EVENT", "Ignored");
        assertThat(ignored.getProcessingStatus()).isEqualTo("IGNORED");
        assertThat(ignored.getErrorCategory()).isEqualTo("UNSUPPORTED_EVENT");
        assertThat(ignored.getNextAttemptAt()).isNull();
    }
}
