package com.banking.notification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailRequest(
        @NotBlank(message = "Recipient is required")
        @Email(message = "Recipient email format is invalid")
        String to,

        @NotBlank(message = "Subject is required")
        String subject,

        @NotBlank(message = "Message is required")
        String message
) {}