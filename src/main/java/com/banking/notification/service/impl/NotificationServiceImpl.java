package com.banking.notification.service.impl;

import com.banking.notification.channel.NotificationChannel;
import com.banking.notification.dto.EmailRequest;
import com.banking.notification.dto.EmailResponse;
import com.banking.notification.dto.TemplatedEmailRequest;
import com.banking.notification.entity.NotificationLog;
import com.banking.notification.entity.NotificationStatus;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.exception.NotificationFailedException;
import com.banking.notification.repository.NotificationLogRepository;
import com.banking.notification.service.NotificationService;
import com.banking.notification.template.EmailContent;
import com.banking.notification.template.NotificationTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationChannel channel;
    private final NotificationLogRepository logRepository;

    public NotificationServiceImpl(NotificationChannel channel,
                                   NotificationLogRepository logRepository) {
        this.channel = channel;
        this.logRepository = logRepository;
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
            return new EmailResponse("SENT");
        } catch (MailException ex) {
            log.error("Failed to send {} email to {}: {}", type, to, ex.getMessage());
            saveLog(to, subject, type, NotificationStatus.FAILED, truncate(ex.getMessage()));
            throw new NotificationFailedException("Failed to send email");
        }
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

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}