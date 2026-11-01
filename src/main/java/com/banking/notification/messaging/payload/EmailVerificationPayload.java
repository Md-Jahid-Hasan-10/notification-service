package com.banking.notification.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailVerificationPayload(
        Long userId,
        String email,
        String fullName,
        String encryptedToken,
        Instant expiresAt
) {}