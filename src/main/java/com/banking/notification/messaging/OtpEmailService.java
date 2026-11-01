package com.banking.notification.messaging;

import com.banking.notification.dto.TemplatedEmailRequest;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.messaging.payload.OtpIssuedPayload;
import com.banking.notification.service.NotificationService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class OtpEmailService {

    private final NotificationService notificationService;
    private final EventCrypto crypto;

    public OtpEmailService(NotificationService notificationService, EventCrypto crypto) {
        this.notificationService = notificationService;
        this.crypto = crypto;
    }

    public void send(OtpIssuedPayload payload) {
        if (isBlank(payload.transferId()) || isBlank(payload.email()) || isBlank(payload.encryptedCode())) {
            throw new InvalidEventException("OtpIssued is missing required fields");
        }

        // The code is only decrypted here, and is never logged.
        String code = crypto.decrypt(payload.encryptedCode(), payload.transferId().trim());

        notificationService.sendTemplated(new TemplatedEmailRequest(
                payload.email().trim(), NotificationType.OTP, Map.of("otp", code)));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}