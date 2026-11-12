package com.codepilot.dto;

import java.util.UUID;

public record WebhookReceiptDto(String deliveryId, String status, UUID eventId) {
}
