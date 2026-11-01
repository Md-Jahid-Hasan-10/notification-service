package com.banking.notification.messaging.payload;

public record OtpDeliveryFailedPayload(String transferId, String reason) {}