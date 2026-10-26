package com.banking.notification.channel;

public interface NotificationChannel {

    void send(String to, String subject, String message);
}