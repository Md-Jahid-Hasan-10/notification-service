package com.banking.notification.service;

import com.banking.notification.dto.EmailRequest;
import com.banking.notification.dto.EmailResponse;
import com.banking.notification.dto.TemplatedEmailRequest;

public interface NotificationService {

    EmailResponse sendEmail(EmailRequest request);

    EmailResponse sendTemplated(TemplatedEmailRequest request);
}