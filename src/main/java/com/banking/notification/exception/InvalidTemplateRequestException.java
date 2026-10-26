package com.banking.notification.exception;

public class InvalidTemplateRequestException extends RuntimeException {
    public InvalidTemplateRequestException(String message) {
        super(message);
    }
}