package com.banking.notification.controller;

import com.banking.notification.dto.EmailRequest;
import com.banking.notification.dto.EmailResponse;
import com.banking.notification.dto.TemplatedEmailRequest;
import com.banking.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    private final NotificationService notificationService;

    public InternalNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/email")
    public ResponseEntity<EmailResponse> sendEmail(@Valid @RequestBody EmailRequest request) {
        return ResponseEntity.ok(notificationService.sendEmail(request));
    }

    @PostMapping("/templated")
    public ResponseEntity<EmailResponse> sendTemplated(@Valid @RequestBody TemplatedEmailRequest request) {
        return ResponseEntity.ok(notificationService.sendTemplated(request));
    }
}