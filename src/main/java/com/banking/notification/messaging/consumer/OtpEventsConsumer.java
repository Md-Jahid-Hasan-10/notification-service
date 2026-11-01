package com.banking.notification.messaging.consumer;

import com.banking.notification.messaging.EventEnvelope;
import com.banking.notification.messaging.EventParser;
import com.banking.notification.messaging.IdempotentProcessor;
import com.banking.notification.messaging.OtpDeliveryFailureAnnouncer;
import com.banking.notification.messaging.OtpEmailService;
import com.banking.notification.messaging.TopicNames;
import com.banking.notification.messaging.payload.OtpIssuedPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OtpEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OtpEventsConsumer.class);
    private static final String HANDLER = "otp-events";

    private final EventParser parser;
    private final IdempotentProcessor processor;
    private final OtpEmailService otpEmailService;
    private final OtpDeliveryFailureAnnouncer announcer;

    public OtpEventsConsumer(EventParser parser,
                             IdempotentProcessor processor,
                             OtpEmailService otpEmailService,
                             OtpDeliveryFailureAnnouncer announcer) {
        this.parser = parser;
        this.processor = processor;
        this.otpEmailService = otpEmailService;
        this.announcer = announcer;
    }

    @KafkaListener(topics = TopicNames.OTP_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        if ("OtpIssued".equals(envelope.eventType())) {
            processor.processOnce(envelope, HANDLER, () -> deliver(envelope));
        } else {
            // OtpIssueRejected and unknown types are not for Notification.
            log.debug("Ignoring event type {}", envelope.eventType());
            processor.ignore(envelope);
        }
    }

    private void deliver(EventEnvelope envelope) {
        OtpIssuedPayload payload = parser.payload(envelope, OtpIssuedPayload.class);

        // A code that has expired would only confuse the user, so it is not emailed.
        if (payload.expiresAt() != null && payload.expiresAt().isBefore(Instant.now())) {
            announcer.announce(payload.transferId(), "The OTP expired before it could be delivered",
                    envelope.eventId());
            return;
        }

        otpEmailService.send(payload);
    }
}