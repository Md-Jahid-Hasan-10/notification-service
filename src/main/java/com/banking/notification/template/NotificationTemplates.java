package com.banking.notification.template;

import com.banking.notification.entity.NotificationType;
import com.banking.notification.exception.InvalidTemplateRequestException;

import java.util.Map;

public final class NotificationTemplates {

    private NotificationTemplates() {}

    public static EmailContent build(NotificationType type, Map<String, String> params) {
        Map<String, String> p = params == null ? Map.of() : params;

        return switch (type) {
            case OTP -> new EmailContent(
                    "Your Banking OTP",
                    "Your one-time password is " + require(p, "otp") + ".\n"
                            + "It expires in 5 minutes. Do not share it with anyone."
            );
            case TRANSFER_SUCCESS -> new EmailContent(
                    "Transfer Successful",
                    "Your transfer was completed.\n"
                            + "Amount: " + require(p, "amount") + "\n"
                            + "From account: " + require(p, "fromAccount") + "\n"
                            + "To account: " + require(p, "toAccount") + "\n"
                            + "Fee: " + p.getOrDefault("fee", "0") + "\n"
                            + "Reference: " + require(p, "reference")
            );
            case TRANSFER_FAILED -> new EmailContent(
                    "Transfer Failed",
                    "Your transfer could not be completed.\n"
                            + "Amount: " + require(p, "amount") + "\n"
                            + "From account: " + require(p, "fromAccount") + "\n"
                            + "To account: " + require(p, "toAccount") + "\n"
                            + "Reason: " + p.getOrDefault("reason", "Unknown")
            );
            case GENERAL -> throw new InvalidTemplateRequestException(
                    "GENERAL has no template, use /internal/notifications/email");
        };
    }

    private static String require(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new InvalidTemplateRequestException("Missing template param: " + key);
        }
        return value;
    }
}