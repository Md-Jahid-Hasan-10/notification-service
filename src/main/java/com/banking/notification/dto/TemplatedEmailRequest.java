package com.banking.notification.dto;

import com.banking.notification.entity.NotificationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record TemplatedEmailRequest(
        @NotBlank(message = "Recipient is required")
        @Email(message = "Recipient email format is invalid")
        String to,

        @NotNull(message = "Type is required")
        NotificationType type,

        Map<String, String> params
) {}