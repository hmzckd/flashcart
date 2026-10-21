package com.hmzckd.notification;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final Notifications notifications;

    NotificationController(Notifications notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    List<NotificationResponse> notifications(@RequestParam long orderId) {
        return notifications.findByOrderId(orderId);
    }
}
