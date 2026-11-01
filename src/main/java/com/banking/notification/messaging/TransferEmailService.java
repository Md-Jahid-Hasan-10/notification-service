package com.banking.notification.messaging;

import com.banking.notification.dto.EmailRequest;
import com.banking.notification.dto.TemplatedEmailRequest;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.messaging.payload.TransferOutcomeEvent;
import com.banking.notification.service.NotificationService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class TransferEmailService {

    private final NotificationService notificationService;

    public TransferEmailService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void send(String eventType, TransferOutcomeEvent event) {
        if (isBlank(event.transferId()) || isBlank(event.email()) || isBlank(event.fromAccountNumber())
                || isBlank(event.toAccountNumber()) || event.amount() == null) {
            throw new InvalidEventException(eventType + " is missing required fields");
        }

        String kind = isBlank(event.type()) ? "TRANSFER" : event.type().trim();
        if ("DEPOSIT".equals(kind) || "WITHDRAWAL".equals(kind)) {
            sendCash(eventType, kind, event);
            return;
        }
        sendTransfer(eventType, event);
    }

    private void sendTransfer(String eventType, TransferOutcomeEvent event) {
        Map<String, String> params = new HashMap<>();
        params.put("amount", event.amount().toPlainString());
        params.put("fromAccount", event.fromAccountNumber());
        params.put("toAccount", event.toAccountNumber());
        params.put("fee", event.fee() == null ? "0.00" : event.fee().toPlainString());
        params.put("reference", event.transferId());

        NotificationType type;
        switch (eventType) {
            case "TransferCompleted" -> type = NotificationType.TRANSFER_SUCCESS;
            case "TransferReversed" -> {
                type = NotificationType.TRANSFER_FAILED;
                params.put("reason", withRefundNote(event.reason()));
            }
            default -> {
                type = NotificationType.TRANSFER_FAILED;
                params.put("reason", isBlank(event.reason()) ? "The transfer failed" : event.reason());
            }
        }

        notificationService.sendTemplated(new TemplatedEmailRequest(event.email().trim(), type, params));
    }

    /** Deposits and withdrawals use a general email, so no new notification type is needed. */
    private void sendCash(String eventType, String kind, TransferOutcomeEvent event) {
        boolean deposit = "DEPOSIT".equals(kind);
        String word = deposit ? "deposit" : "withdrawal";
        String title = deposit ? "Deposit" : "Withdrawal";
        String account = deposit ? event.toAccountNumber() : event.fromAccountNumber();

        String subject;
        String message;
        if ("TransferCompleted".equals(eventType)) {
            subject = title + " successful";
            message = "Hello,\n\nYour " + word + " was completed.\n"
                    + "Account: " + account + "\n"
                    + "Amount: " + event.amount().toPlainString() + "\n"
                    + "Reference: " + event.transferId() + "\n";
        } else {
            subject = title + " failed";
            message = "Hello,\n\nYour " + word + " could not be completed. No money was moved.\n"
                    + "Account: " + account + "\n"
                    + "Amount: " + event.amount().toPlainString() + "\n"
                    + "Reason: " + (isBlank(event.reason()) ? "Unknown" : event.reason()) + "\n"
                    + "Reference: " + event.transferId() + "\n";
        }

        notificationService.sendEmail(new EmailRequest(event.email().trim(), subject, message));
    }

    private String withRefundNote(String reason) {
        String base = isBlank(reason) ? "The receiver could not be credited." : reason.trim();
        return base + " Your money was returned.";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}