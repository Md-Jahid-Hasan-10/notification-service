package com.banking.notification.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OtpIssuedPayload(
        String transferId,
        String otpId,
        String email,
        String encryptedCode,
        Instant expiresAt
) {}