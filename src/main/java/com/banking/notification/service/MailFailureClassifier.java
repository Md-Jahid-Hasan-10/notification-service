package com.banking.notification.service;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Decides whether a failed email is worth trying again.
 * PERMANENT: wrong credentials, a message that cannot be built, an address the server refuses (SMTP 5xx).
 * TEMPORARY: network problems and timeouts, SMTP 4xx, and anything not recognized (a bounded retry is the safe default).
 */
public final class MailFailureClassifier {

    public enum Kind { PERMANENT, TEMPORARY }

    private static final Pattern SMTP_PERMANENT = Pattern.compile("(^|\\s)5\\d\\d[ -]\\d\\.\\d{1,3}\\.\\d{1,3}");
    private static final Pattern SMTP_TEMPORARY = Pattern.compile("(^|\\s)4\\d\\d[ -]\\d\\.\\d{1,3}\\.\\d{1,3}");

    private MailFailureClassifier() {
    }

    public static Kind classify(Throwable error) {
        if (error instanceof MailAuthenticationException
                || error instanceof MailParseException
                || error instanceof MailPreparationException) {
            return Kind.PERMANENT;
        }

        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof AuthenticationFailedException) {
                return Kind.PERMANENT;
            }
            if (current instanceof IOException) {
                return Kind.TEMPORARY;
            }
            String message = current.getMessage();
            if (message != null) {
                if (SMTP_TEMPORARY.matcher(message).find()) {
                    return Kind.TEMPORARY;
                }
                if (SMTP_PERMANENT.matcher(message).find()) {
                    return Kind.PERMANENT;
                }
            }
            if (current instanceof SendFailedException) {
                return Kind.PERMANENT;
            }
            current = current.getCause();
        }
        return Kind.TEMPORARY;
    }
}