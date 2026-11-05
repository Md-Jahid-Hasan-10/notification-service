package com.banking.notification.exception;

/** The email can never be delivered with the current settings (wrong password, invalid address). Not retried. */
public class PermanentDeliveryException extends RuntimeException {
    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}