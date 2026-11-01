package com.banking.notification.messaging.consumer;

import com.banking.notification.messaging.EventEnvelope;
import com.banking.notification.messaging.IdempotentProcessor;
import com.banking.notification.messaging.InvalidEventException;
import com.banking.notification.messaging.TopicNames;
import com.banking.notification.messaging.VerificationEmailService;
import com.banking.notification.messaging.payload.EmailVerificationPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserEventsConsumer.class);
    private static final String HANDLER = "user-events";

    private final ObjectMapper objectMapper;
    private final IdempotentProcessor processor;
    private final VerificationEmailService verificationEmailService;

    public UserEventsConsumer(ObjectMapper objectMapper,
                              IdempotentProcessor processor,
                              VerificationEmailService verificationEmailService) {
        this.objectMapper = objectMapper;
        this.processor = processor;
        this.verificationEmailService = verificationEmailService;
    }

    @KafkaListener(topics = TopicNames.USER_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parse(record.value());

        switch (envelope.eventType()) {
            case "EmailVerificationRequested" -> processor.processOnce(envelope, HANDLER,
                    () -> verificationEmailService.send(readPayload(envelope)));
            case "UserRegistered" -> processor.ignore(envelope);
            default -> {
                // A newer producer may send types we don't know yet. Ignore them, never fail.
                log.debug("Ignoring unknown event type {}", envelope.eventType());
                processor.ignore(envelope);
            }
        }
    }

    private EventEnvelope parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Empty message");
        }
        try {
            EventEnvelope envelope = objectMapper.readValue(value, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventId().isBlank()
                    || envelope.eventType() == null || envelope.eventType().isBlank()) {
                throw new InvalidEventException("Message has no eventId or eventType");
            }
            return envelope;
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Message is not valid JSON", ex);
        }
    }

    private EmailVerificationPayload readPayload(EventEnvelope envelope) {
        if (envelope.payload() == null || envelope.payload().isNull()) {
            throw new InvalidEventException("Message has no payload");
        }
        try {
            return objectMapper.treeToValue(envelope.payload(), EmailVerificationPayload.class);
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Payload does not match EmailVerificationRequested", ex);
        }
    }
}