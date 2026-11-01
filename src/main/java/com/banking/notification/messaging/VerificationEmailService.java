package com.banking.notification.messaging;

import com.banking.notification.dto.EmailRequest;
import com.banking.notification.messaging.payload.EmailVerificationPayload;
import com.banking.notification.service.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class VerificationEmailService {

    private final NotificationService notificationService;
    private final EventCrypto crypto;
    private final String verificationUrl;

    public VerificationEmailService(NotificationService notificationService,
                                    EventCrypto crypto,
                                    @Value("${app.notification.verification-url}") String verificationUrl) {
        this.notificationService = notificationService;
        this.crypto = crypto;
        this.verificationUrl = verificationUrl;
    }

    public void send(EmailVerificationPayload payload) {
        if (payload.userId() == null || isBlank(payload.email()) || isBlank(payload.encryptedToken())) {
            throw new InvalidEventException("EmailVerificationRequested is missing required fields");
        }

        String token = crypto.decrypt(payload.encryptedToken(), String.valueOf(payload.userId()));
        String link = verificationUrl + URLEncoder.encode(token, StandardCharsets.UTF_8);

        String name = isBlank(payload.fullName()) ? "" : " " + payload.fullName().trim();
        String expiry = payload.expiresAt() == null
                ? ""
                : "\nThis link expires at " + payload.expiresAt() + " (UTC).\n";

        String message = "Hello" + name + ",\n\n"
                + "Welcome to Banking. Please verify your email address by opening this link:\n"
                + link + "\n"
                + expiry
                + "\nIf you did not create an account, you can ignore this email.";

        notificationService.sendEmail(new EmailRequest(payload.email().trim(),
                "Verify your email address", message));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}