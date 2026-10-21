package com.hmzckd.notification;

import java.time.Instant;

record NotificationResponse(Long orderId, String type, String text, Instant occurredAt) {
}
