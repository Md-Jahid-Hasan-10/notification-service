package com.banking.notification.exception;

public class NotificationFailedException extends RuntimeException {
    public NotificationFailedException(String message) {
        super(message);
    }
}