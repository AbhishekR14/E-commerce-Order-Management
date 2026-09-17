package com.ecommerce.oms.notification.dto;

import com.ecommerce.oms.notification.NotificationType;
import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String message,
        String referenceType,
        Long referenceId,
        boolean read,
        Instant createdAt) {
}
