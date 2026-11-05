package com.banking.notification.service.impl;

import com.banking.notification.channel.NotificationChannel;
import com.banking.notification.dto.EmailRequest;
import com.banking.notification.dto.EmailResponse;
import com.banking.notification.dto.TemplatedEmailRequest;
import com.banking.notification.entity.NotificationLog;
import com.banking.notification.entity.NotificationStatus;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.exception.NotificationFailedException;
import com.banking.notification.exception.PermanentDeliveryException;
import com.banking.notification.repository.NotificationLogRepository;
import com.banking.notification.service.MailFailureClassifier;
import com.banking.notification.service.NotificationService;
import com.banking.notification.template.EmailContent;
import com.banking.notification.template.NotificationTemplates;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationChannel channel;
    private final NotificationLogRepository logRepository;
    private final MeterRegistry meters;

    public NotificationServiceImpl(NotificationChannel channel,
                                   NotificationLogRepository logRepository,
                                   MeterRegistry meters) {
        this.channel = channel;
        this.logRepository = logRepository;
        this.meters = meters;
    }

    @Override
    public EmailResponse sendEmail(EmailRequest request) {
        return dispatch(request.to(), request.subject(), request.message(), NotificationType.GENERAL);
    }

    @Override
    public EmailResponse sendTemplated(TemplatedEmailRequest request) {
        EmailContent content = NotificationTemplates.build(request.type(), request.params());
        return dispatch(request.to(), content.subject(), content.message(), request.type());
    }

    private EmailResponse dispatch(String to, String subject, String message, NotificationType type) {
        try {
            channel.send(to, subject, message);
            saveLog(to, subject, type, NotificationStatus.SENT, null);
            count(type, "sent");
            return new EmailResponse("SENT");
        } catch (MailException ex) {
            MailFailureClassifier.Kind kind = MailFailureClassifier.classify(ex);
            log.error("Failed to send {} email to {} ({}): {}", type, maskEmail(to), kind, ex.getMessage());
            saveLog(to, subject, type, NotificationStatus.FAILED, kind + ": " + truncate(ex.getMessage()));
            count(type, "failed");
            meters.counter("notifications.email.failures",
                    "type", type.name(), "kind", kind.name().toLowerCase(Locale.ROOT)).increment();

            if (kind == MailFailureClassifier.Kind.PERMANENT) {
                // Trying again cannot help, so the Kafka consumer sends the message to the dead-letter topic at once.
                throw new PermanentDeliveryException("The email cannot be delivered", ex);
            }
            throw new NotificationFailedException("Failed to send email");
        }
    }

    private void count(NotificationType type, String status) {
        meters.counter("notifications.emails", "type", type.name(), "status", status).increment();
    }

    private void saveLog(String to, String subject, NotificationType type,
                         NotificationStatus status, String error) {
        NotificationLog entry = new NotificationLog();
        entry.setRecipient(to);
        entry.setSubject(subject);
        entry.setType(type);
        entry.setStatus(status);
        entry.setErrorMessage(error);
        logRepository.save(entry);
    }

    private String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 480 ? text.substring(0, 480) : text;
    }
}