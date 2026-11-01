package com.banking.notification.messaging.consumer;

import com.banking.notification.messaging.EventEnvelope;
import com.banking.notification.messaging.EventParser;
import com.banking.notification.messaging.IdempotentProcessor;
import com.banking.notification.messaging.TopicNames;
import com.banking.notification.messaging.TransferEmailService;
import com.banking.notification.messaging.payload.TransferOutcomeEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransferEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsConsumer.class);
    private static final String HANDLER = "transfer-events";

    private final EventParser parser;
    private final IdempotentProcessor processor;
    private final TransferEmailService emailService;

    public TransferEventsConsumer(EventParser parser,
                                  IdempotentProcessor processor,
                                  TransferEmailService emailService) {
        this.parser = parser;
        this.processor = processor;
        this.emailService = emailService;
    }

    @KafkaListener(topics = TopicNames.TRANSFER_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        switch (envelope.eventType()) {
            case "TransferCompleted", "TransferFailed", "TransferReversed" ->
                    processor.processOnce(envelope, HANDLER, () -> emailService.send(
                            envelope.eventType(), parser.payload(envelope, TransferOutcomeEvent.class)));
            case "ReconciliationRequired" -> {
                // For operators, not for the customer. Prometheus and the alert rules show it.
                log.warn("Transfer {} needs reconciliation", envelope.correlationId());
                processor.ignore(envelope);
            }
            default -> processor.ignore(envelope);
        }
    }
}