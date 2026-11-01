package com.banking.notification.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferOutcomeEvent(
        String transferId,
        String type,
        Long userId,
        String email,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        BigDecimal fee,
        String reason
) {}